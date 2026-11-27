package com.example.salesbatch.spark

import java.math.BigDecimal
import java.net.{InetSocketAddress, Socket}
import java.util.Arrays

import scala.collection.JavaConverters._

import com.example.salesbatch.common.{HBaseSalesRepository, RowKeys, SalesRecord, SalesSchema}
import org.apache.hadoop.hbase.TableName
import org.apache.hadoop.hbase.client.Delete
import org.apache.hadoop.hbase.util.Bytes
import org.junit.Assert.{assertEquals, assertTrue}
import org.junit.Assume.assumeTrue
import org.junit.{After, Before, Test}

/**
 * Runs the real job (SalesAggregator.main with a local Spark) against a real HBase: reads sales_raw, aggregates,
 * writes sales_agg. It is skipped when no HBase answers on localhost (start the Docker stack to run it).
 * It uses far-future business dates and deletes its own rows, so it does not disturb other data.
 */
class SalesAggregatorHBaseTest {

  private val date = "20990101"
  private val otherDate = "20990102"
  private var repo: HBaseSalesRepository = _

  /** ZooKeeper, the HBase master and the region server must all answer (ZooKeeper alone can outlive a dead HBase). */
  private def hbaseReachable(): Boolean = Seq(2181, 16000, 16020).forall { port =>
    val socket = new Socket()
    try {
      socket.connect(new InetSocketAddress("localhost", port), 500)
      true
    } catch {
      case _: Exception => false
    } finally {
      socket.close()
    }
  }

  @Before def setUp(): Unit = {
    assumeTrue("HBase is not running on localhost (2181, 16000, 16020), skipping", hbaseReachable())
    repo = new HBaseSalesRepository()
    repo.initSchema()
    cleanUp()
  }

  @After def tearDown(): Unit = {
    if (repo != null) {
      cleanUp()
      repo.close()
    }
  }

  private def cleanUp(): Unit = {
    for (d <- Seq(date, otherDate)) {
      val raw = repo.connection().getTable(TableName.valueOf(SalesSchema.RAW_TABLE))
      val agg = repo.connection().getTable(TableName.valueOf(SalesSchema.AGG_TABLE))
      try {
        repo.loadRaw(d).asScala.foreach(r => raw.delete(new Delete(Bytes.toBytes(RowKeys.raw(r.getDate, r.getOrderId)))))
        repo.loadAgg(d).asScala.foreach(a => agg.delete(new Delete(Bytes.toBytes(RowKeys.agg(a.getDate, a.getRegion, a.getProductId)))))
      } finally {
        raw.close()
        agg.close()
      }
    }
  }

  private def runJob(businessDate: String): Unit = {
    System.setProperty("spark.master", "local[2]")
    System.setProperty("spark.ui.enabled", "false")
    try SalesAggregator.main(Array(businessDate))
    finally {
      System.clearProperty("spark.master")
      System.clearProperty("spark.ui.enabled")
    }
  }

  @Test(timeout = 180000) def jobAggregatesOnlyTheRequestedDate(): Unit = {
    repo.saveRaw(Arrays.asList(
      new SalesRecord(date, "T1", "P1", "EU", 2, new BigDecimal("10.50")),
      new SalesRecord(date, "T2", "P1", "EU", 3, new BigDecimal("15.75")),
      new SalesRecord(date, "T3", "P2", "US", 1, new BigDecimal("4.00")),
      new SalesRecord(otherDate, "T4", "P9", "APAC", 7, new BigDecimal("70.00"))))

    runJob(date)

    val rows = repo.loadAgg(date).asScala
    assertEquals(2, rows.size)
    val eu = rows.find(a => a.getRegion == "EU" && a.getProductId == "P1").get
    assertEquals(5L, eu.getTotalQty)
    assertEquals(new BigDecimal("26.25"), eu.getTotalAmount)
    assertEquals(2L, eu.getOrderCount)
    val us = rows.find(a => a.getRegion == "US" && a.getProductId == "P2").get
    assertEquals(1L, us.getTotalQty)
    assertEquals(1L, us.getOrderCount)
    assertTrue("the other date must not be aggregated", repo.loadAgg(otherDate).isEmpty)
  }

  @Test(timeout = 180000) def jobWithoutRowsWritesNothing(): Unit = {
    runJob(date)

    assertTrue(repo.loadAgg(date).isEmpty)
  }
}
