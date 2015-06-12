/*
 * Copyright 2015 Midokura SARL
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

package org.midonet.midolman.host.scanner

import java.util.concurrent.CountDownLatch

import scala.collection.JavaConversions._
import scala.concurrent.Promise
import scala.sys.process._

import com.typesafe.scalalogging.Logger
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{BeforeAndAfterAll, FeatureSpec, Matchers}
import org.scalatest.time._
import org.slf4j.LoggerFactory
import rx.{Subscription, Observer}

import org.midonet.midolman.host.interfaces.InterfaceDescription
import org.midonet.odp.util.TapWrapper
import org.midonet.packets.IPv4Addr
import org.midonet.util.IntegrationTests._

object InterfaceScannerTest {
    val TEST_IF_NAME = "if-scanner-test"
    val TEST_IP_ADDR = "192.168.142.42"
    val TEST_TIMEOUT_SPAN = Span(2, Seconds)
}

@RunWith(classOf[JUnitRunner])
class InterfaceScannerTest extends FeatureSpec
                           with BeforeAndAfterAll
                           with Matchers
                           with ScalaFutures {
    import InterfaceScannerTest._

    val scanner: InterfaceScanner = DefaultInterfaceScanner()
    val logger = Logger(LoggerFactory.getLogger(classOf[InterfaceScannerTest]))
    private var interfaceDescriptions: Set[InterfaceDescription] = Set.empty

    override def beforeAll(): Unit = {
        val initialScanSignal = new CountDownLatch(1)
        scanner.start()
        scanner.subscribe(new Observer[Set[InterfaceDescription]] {
            override def onCompleted(): Unit =
                logger.debug("notification observer is completed.")
            override def onError(t: Throwable): Unit =
                logger.error(s"notification observer got the error $t")
            override def onNext(descs: Set[InterfaceDescription]): Unit = {
                logger.debug(s"notification observer got the ifDescs: ", descs)
                interfaceDescriptions = descs
                initialScanSignal.countDown()
            }
        })
        initialScanSignal.await()
    }

    override def afterAll(): Unit = {
        scanner.stop()
    }

    feature("Interface scanner") {
        scenario("Creating a new link triggers the notification") {
            implicit val promise = Promise[String]()

            val originalIfDescSize: Int = interfaceDescriptions.size

            scanner.subscribe(new Observer[Set[InterfaceDescription]] {
                private var initialScanned = false
                val obs = TestObserver { descs: Set[InterfaceDescription] =>
                    descs.exists(desc => desc.getName == TEST_IF_NAME)
                }
                override def onCompleted(): Unit = obs.onCompleted()
                override def onError(t: Throwable): Unit = obs.onError(t)
                override def onNext(descs: Set[InterfaceDescription]): Unit = {
                    if (initialScanned &&
                        (descs.size == (originalIfDescSize + 1))) {
                        obs.onNext(descs)
                        obs.onCompleted()
                    } else {
                        initialScanned = true  // Ignore the initial scan.
                    }
                }
            })

            val tap = new TapWrapper(TEST_IF_NAME, true)
            tap.up()

            try {
                whenReady(promise.future, timeout(TEST_TIMEOUT_SPAN)) {
                    result: String  => result should be (OK)
                }
            } finally {
                tap.down()
                tap.remove()
            }
        }

        scenario("Deleting a new link triggers the notification") {
            implicit val promise = Promise[String]()
            val tap = new TapWrapper(TEST_IF_NAME, true)
            tap.up()

            val originalIfDescSize: Int = interfaceDescriptions.size

            tap.down()

            scanner.subscribe(new Observer[Set[InterfaceDescription]] {
                private var initialScanned = false
                val obs = TestObserver { descs: Set[InterfaceDescription] =>
                    !descs.exists(desc => desc.getName == TEST_IF_NAME)
                }
                override def onCompleted(): Unit = obs.onCompleted()
                override def onError(t: Throwable): Unit = obs.onError(t)
                override def onNext(descs: Set[InterfaceDescription]): Unit = {
                    if (initialScanned &&
                        (descs.size == (originalIfDescSize - 1))) {
                        obs.onNext(descs)
                        obs.onCompleted()
                    } else {
                        initialScanned = true  // Ignore the initial scan.
                    }
                }
            })

            tap.remove()

            whenReady(promise.future, timeout(TEST_TIMEOUT_SPAN)) {
                result: String => result should be (OK)
            }
        }

        scenario("Creating a new addr triggers the notification") {
            implicit val promise = Promise[String]()
            val tap = new TapWrapper(TEST_IF_NAME, true)
            tap.up()

            val originalIfDescSize: Int = interfaceDescriptions.size

            scanner.subscribe(new Observer[Set[InterfaceDescription]] {
                private var initialScanned = false
                val obs = TestObserver { descs: Set[InterfaceDescription] =>
                    descs.exists(desc =>
                        desc.getInetAddresses.exists(inetAddr =>
                            inetAddr.getAddress.length == 4 &&
                                IPv4Addr.fromBytes(inetAddr.getAddress) ==
                                    IPv4Addr.fromString(TEST_IP_ADDR)))
                }
                override def onCompleted(): Unit = obs.onCompleted()
                override def onError(t: Throwable): Unit = obs.onError(t)
                override def onNext(descs: Set[InterfaceDescription]): Unit = {
                    if (initialScanned && (descs.size == originalIfDescSize)) {
                        obs.onNext(descs)
                        obs.onCompleted()
                    } else {
                        initialScanned = true  // Ignore the initial scan.
                    }
                }
            })

            if (s"ip a add $TEST_IP_ADDR dev $TEST_IF_NAME".! != 0) {
                promise.failure(TestPrepareException)
            }

            try {
                whenReady(promise.future, timeout(TEST_TIMEOUT_SPAN)) {
                    result: String => result should be (OK)
                }
            } finally {
                s"ip address flush dev $TEST_IF_NAME".!
                tap.down()
                tap.remove()
            }
        }

        scenario("Deleting a new addr triggers the notification") {
            implicit val promise = Promise[String]()
            val tap = new TapWrapper(TEST_IF_NAME, true)
            tap.up()

            val originalIfDescSize: Int = interfaceDescriptions.size

            if (s"ip a add $TEST_IP_ADDR dev $TEST_IF_NAME".! != 0) {
                promise.failure(TestPrepareException)
            }

            scanner.subscribe(new Observer[Set[InterfaceDescription]] {
                private var initialScanned = false
                val obs = TestObserver { descs: Set[InterfaceDescription] =>
                    !descs.exists(desc =>
                        desc.getInetAddresses.exists(inetAddr =>
                            inetAddr.getAddress.length == 4 &&
                                IPv4Addr.fromBytes(inetAddr.getAddress) ==
                                    IPv4Addr.fromString(TEST_IP_ADDR)))
                }
                override def onCompleted(): Unit = obs.onCompleted()
                override def onError(t: Throwable): Unit = obs.onError(t)
                override def onNext(descs: Set[InterfaceDescription]): Unit = {
                    if (initialScanned && (descs.size == originalIfDescSize)) {
                        obs.onNext(descs)
                        obs.onCompleted()
                    } else {
                        initialScanned = true  // Ignore the initial scan.
                    }
                }
            })

            if (s"ip address del $TEST_IP_ADDR dev $TEST_IF_NAME".! != 0) {
                promise.tryFailure(TestPrepareException)
            }


            try {
                whenReady(promise.future, timeout(TEST_TIMEOUT_SPAN)) {
                    result: String => result should be (OK)
                }
            } finally {
                tap.down()
                tap.remove()
            }
        }

        scenario("""Subscribing the interface scanner should notify the
                   |current interface descriptions.
                 """.stripMargin.replaceAll("\n", " ")) {
            implicit val promise = Promise[String]()
            val tap = new TapWrapper(TEST_IF_NAME, true)
            tap.up()

            val subscription: Subscription =
                scanner.subscribe(new Observer[Set[InterfaceDescription]] {
                    private var initialScanned = false
                    val obs = TestObserver { descs: Set[InterfaceDescription] =>
                        descs == interfaceDescriptions
                    }

                    override def onCompleted(): Unit = obs.onCompleted()

                    override def onError(t: Throwable): Unit = obs.onError(t)

                    override
                    def onNext(descs: Set[InterfaceDescription]): Unit =
                        if (!initialScanned) {
                            obs.onNext(descs)
                            obs.onCompleted()
                            initialScanned = true
                        }
                })

            try {
                whenReady(promise.future, timeout(TEST_TIMEOUT_SPAN)) {
                    result: String => result should be (OK)
                }
            } finally {
                subscription.unsubscribe()
                tap.down()
                tap.remove()
            }
        }
    }
}