/*
 * Copyright (c) 2013 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.util.collection

import java.util.concurrent.Semaphore

import scala.concurrent._
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext
import scala.util.Failure

import org.scalatest.{Matchers, FeatureSpec}
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.util.concurrent._

@RunWith(classOf[JUnitRunner])
class FutureOpsTest extends FeatureSpec with Matchers {
    implicit val ex = ExecutionContext.global

    trait TestError

    feature("FutureCompanionOps::sequentially executes futures sequentially") {
        scenario("all futures are successfully executed sequentially") {
            val f = Future.sequentially(1 to 3)(Future(_)) map {
                _ should contain allOf (1, 2, 3)
            }

            Await.result(f, 500 millis)
        }

        scenario("after a failure, remaining futures are not executed") {
            var i = 0
            val f = Future.sequentially(1 to 3) { x =>
                future {
                    if (x == 2) throw new Exception with TestError
                    i += x
                    x
                }
            }

            intercept[Exception with TestError] {
                Await.result(f, 500 millis)
            }
            i should be (1)
        }
    }

    feature("FutureOps::continueWith executes after a future is completed") {
        scenario("The same future is passed into continueWith") {
            val sem = new Semaphore(0)
            val f1 = future { sem.acquire() }
            val f2 = f1 continueWith { f =>
                f should be (f1)
                f.isCompleted should be (true)
            }

            intercept[TimeoutException] {
                Await.result(f2, 500 millis)
            }

            sem.release()
            Await.result(f2, 500 millis)
        }

        scenario("The result of the future is reflected in continue") {
            val sem = new Semaphore(0)
            val f1 = future { sem.acquire(); throw new Exception with TestError }
            val f2 = f1 continue {
                case Failure(t: Exception with TestError) =>
                case _ => fail()
            }

            intercept[TimeoutException] {
                Await.result(f2, 500 millis)
            }

            sem.release()
            Await.result(f2, 500 millis)
        }
    }
}
