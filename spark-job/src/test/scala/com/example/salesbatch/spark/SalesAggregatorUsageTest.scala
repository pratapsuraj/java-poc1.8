package com.example.salesbatch.spark

import org.junit.Assert.{assertTrue, fail}
import org.junit.Test

/** Argument checking of the job entry point. Needs neither Spark nor HBase. */
class SalesAggregatorUsageTest {

  private def assertRejected(args: Array[String]): Unit =
    try {
      SalesAggregator.main(args)
      fail("expected the arguments " + args.mkString("[", ", ", "]") + " to be rejected")
    } catch {
      case e: IllegalArgumentException => assertTrue(e.getMessage, e.getMessage.contains("Usage"))
    }

  @Test def aMissingDateIsRejected(): Unit = assertRejected(Array.empty[String])

  @Test def aMalformedDateIsRejected(): Unit = assertRejected(Array("2026-11-13"))

  @Test def extraArgumentsAreRejected(): Unit = assertRejected(Array("20261113", "extra"))
}
