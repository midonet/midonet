/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.util

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.util.{Try, Success, Failure}

object IntegrationTests {

    type TestSuite = Seq[(String, Future[Any])]

    type Report = Seq[(String, Try[String])]

    def printReport(r: Report) = r.foldLeft(true) {
        case (status, result) => if (status) printTest(result) else false
    }

    def printTest(info: (String, Try[String])) = info match {
        case (desc, Success(msg)) =>
            Console println "[o] " + desc
            true
        case (desc, Failure(ex)) =>
            Console println "[x] " + desc
            ex.printStackTrace
            false
    }

    def runSuite(ts: TestSuite): Report = toReport(ts)

    def toReport(ts: TestSuite): Stream[(String,Try[String])] = ts match {
        case Nil => Stream.empty
        case (d,t) :: tail =>
            val r = Try(Await.result(t, 2 seconds)) map { case _ => "passed" }
            (d,r) #:: (if (r.isSuccess) toReport(tail) else Stream.empty)
    }

}
