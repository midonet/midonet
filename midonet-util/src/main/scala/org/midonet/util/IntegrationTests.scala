/*
 * Copyright 2014 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
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
