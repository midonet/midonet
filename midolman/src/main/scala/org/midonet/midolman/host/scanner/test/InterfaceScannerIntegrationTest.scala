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

package org.midonet.midolman.host.scanner.test

import java.util.concurrent.CountDownLatch

import scala.collection.JavaConversions._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Promise
import scala.sys.process._

import com.typesafe.scalalogging.Logger
import org.slf4j.LoggerFactory
import rx.{Subscription, Observer}

import org.midonet.midolman.host.interfaces.InterfaceDescription
import org.midonet.midolman.host.scanner.{DefaultInterfaceScanner, InterfaceScanner}
import org.midonet.odp.util.TapWrapper
import org.midonet.packets.IPv4Addr
import org.midonet.util.IntegrationTests._

object InterfaceScannerIntegrationTest {
    val TestIfName = "if-scanner-test"
    val TestIpAddr = "192.168.142.42"
}

trait InterfaceScannerIntegrationTest {
    import InterfaceScannerIntegrationTest._

    val scanner: InterfaceScanner
    val logger: Logger
    private var interfaceDescriptions: Set[InterfaceDescription] = Set.empty

    def start(): Unit = {
        val initialScanSignal = new CountDownLatch(1)
        scanner.start()
        scanner.subscribe(new Observer[Set[InterfaceDescription]] {
            override def onCompleted(): Unit =
                logger.debug("notification observer is completed.")
            override def onError(t: Throwable): Unit =
                logger.error(s"notification observer got the error $t")
            override def onNext(ifDescs: Set[InterfaceDescription]): Unit = {
                logger.debug(s"notification observer got the ifDescs: ", ifDescs)
                interfaceDescriptions = ifDescs
                initialScanSignal.countDown()
            }
        })
        initialScanSignal.await()
    }

    def stop(): Unit = {
        scanner.stop()
    }

    def newLinkTest: Test = {
        val desc = """Creating a new link triggers the notification
                   """.stripMargin.replaceAll("\n", " ")
        implicit val promise = Promise[String]()

        val originalIfDescSize: Int = interfaceDescriptions.size

        scanner.subscribe(new Observer[Set[InterfaceDescription]] {
            private var initialScanned = false
            val obs = TestObserver { ifDescs: Set[InterfaceDescription] =>
                ifDescs.exists(ifDesc => ifDesc.getName == TestIfName)
            }
            override def onCompleted(): Unit = obs.onCompleted()
            override def onError(t: Throwable): Unit = obs.onError(t)
            override def onNext(ifDescs: Set[InterfaceDescription]): Unit = {
                if (initialScanned &&
                    (ifDescs.size == (originalIfDescSize + 1))) {
                    obs.onNext(ifDescs)
                    obs.onCompleted()
                } else {
                    initialScanned = true  // Ignore the initial scan.
                }
            }
        })

        val tap = new TapWrapper(TestIfName, true)
        tap.up()

        promise.future.andThen { case _ =>
            tap.down()
            tap.remove()
        }

        (desc, promise.future)
    }
    val NewLinkTest: LazyTest = () => newLinkTest

    def delLinkTest: Test = {
        val desc = """Deleting a new link triggers the notification
                   """.stripMargin.replaceAll("\n", " ")
        implicit val promise = Promise[String]()
        val tap = new TapWrapper(TestIfName, true)
        tap.up()

        val originalIfDescSize: Int = interfaceDescriptions.size

        tap.down()

        scanner.subscribe(new Observer[Set[InterfaceDescription]] {
            private var initialScanned = false
            val obs = TestObserver { ifDescs: Set[InterfaceDescription] =>
                !ifDescs.exists(ifDesc => ifDesc.getName == TestIfName)
            }
            override def onCompleted(): Unit = obs.onCompleted()
            override def onError(t: Throwable): Unit = obs.onError(t)
            override def onNext(ifDescs: Set[InterfaceDescription]): Unit = {
                if (initialScanned &&
                    (ifDescs.size == (originalIfDescSize - 1))) {
                    obs.onNext(ifDescs)
                    obs.onCompleted()
                } else {
                    initialScanned = true  // Ignore the initial scan.
                }
            }
        })

        tap.remove()

        (desc, promise.future)
    }
    val DelLinkTest: LazyTest = () => delLinkTest

    def newAddrTest: Test = {
        val desc = """Creating a new addr triggers the notification
                   """.stripMargin.replaceAll("\n", " ")
        implicit val promise = Promise[String]()
        val tap = new TapWrapper(TestIfName, true)
        tap.up()

        val originalIfDescSize: Int = interfaceDescriptions.size

        scanner.subscribe(new Observer[Set[InterfaceDescription]] {
            private var initialScanned = false
            val obs = TestObserver { ifDescs: Set[InterfaceDescription] =>
                ifDescs.exists(ifDesc =>
                    ifDesc.getInetAddresses.exists(inetAddr =>
                        inetAddr.getAddress.length == 4 &&
                            IPv4Addr.fromBytes(inetAddr.getAddress) ==
                                IPv4Addr.fromString(TestIpAddr)))
            }
            override def onCompleted(): Unit = obs.onCompleted()
            override def onError(t: Throwable): Unit = obs.onError(t)
            override def onNext(ifDescs: Set[InterfaceDescription]): Unit = {
                if (initialScanned && (ifDescs.size == originalIfDescSize)) {
                    obs.onNext(ifDescs)
                    obs.onCompleted()
                } else {
                    initialScanned = true  // Ignore the initial scan.
                }
            }
        })

        if (s"ip a add $TestIpAddr dev $TestIfName".! != 0) {
            promise.failure(TestPrepareException)
        }

        promise.future.andThen { case _ =>
            s"ip address flush dev $TestIfName".!
            tap.down()
            tap.remove()
        }

        (desc, promise.future)
    }
    val NewAddrTest: LazyTest = () => newAddrTest

    def delAddrTest: Test = {
        val desc = """Deleting a new addr triggers the notification
                   """.stripMargin.replaceAll("\n", " ")
        implicit val promise = Promise[String]()
        val tap = new TapWrapper(TestIfName, true)
        tap.up()

        val originalIfDescSize: Int = interfaceDescriptions.size

        if (s"ip a add $TestIpAddr dev $TestIfName".! != 0) {
            promise.failure(TestPrepareException)
        }

        scanner.subscribe(new Observer[Set[InterfaceDescription]] {
            private var initialScanned = false
            val obs = TestObserver { ifDescs: Set[InterfaceDescription] =>
                !ifDescs.exists(ifDesc =>
                    ifDesc.getInetAddresses.exists(inetAddr =>
                        inetAddr.getAddress.length == 4 &&
                            IPv4Addr.fromBytes(inetAddr.getAddress) ==
                                IPv4Addr.fromString(TestIpAddr)))
            }
            override def onCompleted(): Unit = obs.onCompleted()
            override def onError(t: Throwable): Unit = obs.onError(t)
            override def onNext(ifDescs: Set[InterfaceDescription]): Unit = {
                if (initialScanned && (ifDescs.size == originalIfDescSize)) {
                    obs.onNext(ifDescs)
                    obs.onCompleted()
                } else {
                    initialScanned = true  // Ignore the initial scan.
                }
            }
        })

        if (s"ip address del $TestIpAddr dev $TestIfName".! != 0) {
            promise.tryFailure(TestPrepareException)
        }

        promise.future.andThen { case _ =>
            tap.down()
            tap.remove()
        }

        (desc, promise.future)
    }
    val DelAddrTest: LazyTest = () => delAddrTest

    def initialSubscriptionTest: Test = {
        val desc = """Subscribing the interface scanner should notify the
                   |current interface descriptions.
                   """.stripMargin.replaceAll("\n", " ")
        implicit val promise = Promise[String]()
        val tap = new TapWrapper(TestIfName, true)
        tap.up()

        val subscription: Subscription =
            scanner.subscribe(new Observer[Set[InterfaceDescription]] {
                private var initialScanned = false
                val obs = TestObserver { ifDescs: Set[InterfaceDescription] =>
                    ifDescs == interfaceDescriptions
                }

                override def onCompleted(): Unit = obs.onCompleted()

                override def onError(t: Throwable): Unit = obs.onError(t)

                override def onNext(ifDescs: Set[InterfaceDescription]): Unit =
                    if (!initialScanned) {
                        obs.onNext(ifDescs)
                        obs.onCompleted()
                        initialScanned = true
                    }
            })

        promise.future.andThen { case _ =>
            subscription.unsubscribe()
            tap.down()
            tap.remove()
        }

        (desc, promise.future)
    }
    val InitialSubscriptionTest: LazyTest = () => initialSubscriptionTest
    val SubscriptionTests: LazyTestSuite = Seq(InitialSubscriptionTest)

    val LinkTests: LazyTestSuite = Seq(NewLinkTest, DelLinkTest)
    val AddrTests: LazyTestSuite = Seq(NewAddrTest, DelAddrTest)
}

class DefaultInterfaceScannerIntegrationTestBase
        extends InterfaceScannerIntegrationTest {
    override val scanner = DefaultInterfaceScanner()
    override val logger: Logger = Logger(LoggerFactory.getLogger(
        classOf[DefaultInterfaceScannerIntegrationTestBase]))

    def run(): Boolean = {
        var passed = true
        try {
            start()
            passed &= printReport(runLazySuite(LinkTests))
            passed &= printReport(runLazySuite(AddrTests))
            passed &= printReport(runLazySuite(SubscriptionTests))
        } finally {
            stop()
        }
        passed
    }

    def main(args: Array[String]): Unit =
        System.exit(if (run()) 0 else 1)
}

object DefaultInterfaceScannerIntegrationTest
        extends DefaultInterfaceScannerIntegrationTestBase
