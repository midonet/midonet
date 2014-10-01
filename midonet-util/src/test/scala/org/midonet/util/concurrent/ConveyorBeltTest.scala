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
package org.midonet.util.concurrent

import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Promise, Future}

import org.junit.runner.RunWith

import org.scalatest.junit.JUnitRunner
import org.scalatest.{OneInstancePerTest, Matchers, FeatureSpec}

import org.midonet.util.concurrent._

@RunWith(classOf[JUnitRunner])
class ConveyorBeltTest extends FeatureSpec with Matchers {
    implicit val ec = ExecutionContext.callingThread
    feature ("single lane conveyor belt") {
        scenario ("cargo is handled immediately if lane is free") {
            val belt = new ConveyorBelt(_ => { })
            var i = 0
            belt.handle(() => {
                i += 1
                Future.successful(null)
            })
            i should be (1)
        }

        scenario ("cargo is queued if lane is free") {
            val belt = new ConveyorBelt(_ => { })
            val promise = Promise[Any]()
            var i = 0
            belt.handle(() => {
                i += 1
                belt.handle(() => {
                    i should be (2)
                    i += 1
                    Future.successful(null)
                })
                promise.future andThen { case _ => i += 1 }
            })
            i should be (1)
            promise.success(null)
            i should be (3)
        }

        scenario ("faulty cargo is handled") {
            var exc: Throwable = null
            val toThrow = new Exception
            val belt = new ConveyorBelt(exc = _)
            belt.handle(() => Future.failed(toThrow))
            exc should be (toThrow)
        }
    }

    feature ("multi lane conveyor belt") {
        scenario ("lanes are independent") {
            val belt = new MultiLaneConveyorBelt[String](_ => { })
            val ps = mutable.ListBuffer[Promise[Any]]()
            var i = 0
            val cargo = (k: String, shutdown: () => Unit) => {
                i += 1
                val p = Promise[Any]()
                ps += p
                p.future andThen { case _ => i += 1 }
            }

            belt.handle("1", cargo)
            belt.handle("2", cargo)
            i should be (2)
            var j = 0
            ps foreach { p =>
                p.success(null)
                j += 1
                i should be (2 + j)
            }
        }

        scenario ("shutdown removes a lane") {
            val belt = new MultiLaneConveyorBelt[String](_ => { })
            belt.handle("key", (k, shutdown) => {
                k should be ("key")
                belt containsLane "key" should be (true)
                shutdown()
                Future.successful(null)
            })
            belt containsLane "key" should be (false)
        }

        scenario ("a lane is removed only when it's empty ") {
            val belt = new MultiLaneConveyorBelt[String](_ => { })
            val p = Promise[Any]()
            belt.handle("key", (k, shutdown) => {
                k should be ("key")
                belt containsLane "key" should be (true)
                shutdown()
                belt.handle("key", (k, shutdown) => {
                    shutdown()
                    Future.successful(null)
                })
                p.future
            })

            belt containsLane "key" should be (true)
            p.success(null)
            belt containsLane "key" should be (false)
        }
    }
}
