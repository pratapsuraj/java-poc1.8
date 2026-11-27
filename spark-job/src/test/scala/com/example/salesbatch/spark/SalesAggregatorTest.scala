package com.example.salesbatch.spark

import org.apache.spark.{SparkConf, SparkContext}
import org.junit.Assert.assertEquals
import org.junit.{After, Before, Test}

class SalesAggregatorTest {

  private var sc: SparkContext = _

  @Before def start(): Unit = {
    sc = new SparkContext(new SparkConf().setMaster("local[2]").setAppName("test").set("spark.ui.enabled", "false"))
  }

  @After def stop(): Unit = sc.stop()

  @Test def aggregatesByDateRegionAndProduct(): Unit = {
    val sales = sc.parallelize(Seq(
      RawSale("20261113", "EU", "P1", 2, BigDecimal("10.50")),
      RawSale("20261113", "EU", "P1", 3, BigDecimal("15.75")),
      RawSale("20261113", "EU", "P2", 1, BigDecimal("4.00")),
      RawSale("20261113", "US", "P1", 5, BigDecimal("30.00"))), 2)

    val result = SalesAggregator.aggregate(sales).collectAsMap()

    assertEquals(3, result.size)
    assertEquals(Agg(5, BigDecimal("26.25"), 2), result(("20261113", "EU", "P1")))
    assertEquals(Agg(1, BigDecimal("4.00"), 1), result(("20261113", "EU", "P2")))
    assertEquals(Agg(5, BigDecimal("30.00"), 1), result(("20261113", "US", "P1")))
  }

  @Test def emptyInputGivesNoRows(): Unit = {
    val result = SalesAggregator.aggregate(sc.parallelize(Seq.empty[RawSale], 1)).collect()
    assertEquals(0, result.length)
  }
}
