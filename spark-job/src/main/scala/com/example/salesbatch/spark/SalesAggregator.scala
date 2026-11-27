package com.example.salesbatch.spark

import com.example.salesbatch.common.{RowKeys, SalesSchema}
import org.apache.hadoop.hbase.HBaseConfiguration
import org.apache.hadoop.hbase.TableName
import org.apache.hadoop.hbase.client.{ConnectionFactory, Put, Result, Scan}
import org.apache.hadoop.hbase.filter.{CompareFilter, RegexStringComparator, RowFilter}
import org.apache.hadoop.hbase.io.ImmutableBytesWritable
import org.apache.hadoop.hbase.mapreduce.{TableInputFormat, TableMapReduceUtil}
import org.apache.hadoop.hbase.util.Bytes
import org.apache.spark.rdd.RDD
import org.apache.spark.{SparkConf, SparkContext}

/** One row of sales_raw. */
case class RawSale(date: String, region: String, productId: String, qty: Long, amount: BigDecimal)

/** Aggregated figures for one (date, region, product). */
case class Agg(totalQty: Long, totalAmount: BigDecimal, orderCount: Long)

/**
 * Reads one business date from sales_raw, aggregates by (date, region, product) and writes sales_agg.
 *
 * Usage: spark-submit --class com.example.salesbatch.spark.SalesAggregator spark-job.jar yyyyMMdd
 */
object SalesAggregator {

  /** Pure aggregation, kept free of HBase so it can be unit tested with a local SparkContext. */
  def aggregate(sales: RDD[RawSale]): RDD[((String, String, String), Agg)] =
    sales
      .map(s => ((s.date, s.region, s.productId), Agg(s.qty, s.amount, 1L)))
      .reduceByKey((a, b) => Agg(a.totalQty + b.totalQty, a.totalAmount + b.totalAmount, a.orderCount + b.orderCount))

  def main(args: Array[String]): Unit = {
    require(args.length == 1 && args(0).matches("\\d{8}"), "Usage: SalesAggregator <yyyyMMdd>")
    val date = args(0)

    val sc = new SparkContext(new SparkConf().setAppName("SalesAggregator-" + date))
    try {
      val aggregated = aggregate(readRaw(sc, date))
      val written = writeAgg(sc, aggregated)
      println(s"SalesAggregator finished for $date: $written aggregate rows written")
    } finally {
      sc.stop()
    }
  }

  private def readRaw(sc: SparkContext, date: String): RDD[RawSale] = {
    val conf = HBaseConfiguration.create()
    conf.set(TableInputFormat.INPUT_TABLE, SalesSchema.RAW_TABLE)

    val scan = new Scan()
    scan.addFamily(SalesSchema.RAW_CF)
    scan.setCaching(500)
    scan.setCacheBlocks(false)
    scan.setFilter(new RowFilter(CompareFilter.CompareOp.EQUAL, new RegexStringComparator(RowKeys.rawDateRegex(date))))
    conf.set(TableInputFormat.SCAN, TableMapReduceUtil.convertScanToString(scan))

    sc.newAPIHadoopRDD(conf, classOf[TableInputFormat], classOf[ImmutableBytesWritable], classOf[Result])
      .map { case (_, row) => toRawSale(row) } // convert at once: Result is not serializable
  }

  private def toRawSale(row: Result): RawSale = {
    def col(q: Array[Byte]): String = Bytes.toString(row.getValue(SalesSchema.RAW_CF, q))
    RawSale(
      RowKeys.dateOfRaw(Bytes.toString(row.getRow)),
      col(SalesSchema.REGION),
      col(SalesSchema.PRODUCT_ID),
      col(SalesSchema.QTY).toLong,
      BigDecimal(col(SalesSchema.AMOUNT)))
  }

  private def writeAgg(sc: SparkContext, aggregated: RDD[((String, String, String), Agg)]): Long = {
    // Executors build their own HBase configuration; hand over the ZooKeeper settings found on the driver.
    val driverConf = HBaseConfiguration.create()
    val settings = Seq("hbase.zookeeper.quorum", "hbase.zookeeper.property.clientPort", "zookeeper.znode.parent")
      .flatMap(k => Option(driverConf.get(k)).map(k -> _))
    val counter = sc.longAccumulator("aggRowsWritten")

    aggregated.foreachPartition { rows =>
      val conf = HBaseConfiguration.create()
      settings.foreach { case (k, v) => conf.set(k, v) }
      val connection = ConnectionFactory.createConnection(conf)
      try {
        val table = connection.getTable(TableName.valueOf(SalesSchema.AGG_TABLE))
        try {
          rows.foreach { case ((date, region, product), agg) =>
            val put = new Put(Bytes.toBytes(RowKeys.agg(date, region, product)))
            put.addColumn(SalesSchema.AGG_CF, SalesSchema.TOTAL_QTY, Bytes.toBytes(agg.totalQty.toString))
            put.addColumn(SalesSchema.AGG_CF, SalesSchema.TOTAL_AMOUNT, Bytes.toBytes(agg.totalAmount.bigDecimal.toPlainString))
            put.addColumn(SalesSchema.AGG_CF, SalesSchema.ORDER_COUNT, Bytes.toBytes(agg.orderCount.toString))
            table.put(put)
            counter.add(1L)
          }
        } finally {
          table.close()
        }
      } finally {
        connection.close()
      }
    }
    counter.value
  }
}
