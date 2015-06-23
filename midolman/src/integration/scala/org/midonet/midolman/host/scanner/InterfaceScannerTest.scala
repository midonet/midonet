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

import scala.collection.JavaConversions._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Promise
import scala.sys.process._

import com.typesafe.scalalogging.Logger
import org.midonet.netlink.NetlinkChannelFactory

import org.slf4j.LoggerFactory
import rx.Observer

import org.midonet.midolman.host.scanner.InterfaceScanner._
import org.midonet.odp.util.TapWrapper
import org.midonet.packets.IPv4Addr
import org.midonet.util.IntegrationTests._

object InterfaceScannerTest {
    val TestIfName = "if-scanner-test"
    val TestIpAddr = "192.168.142.42"
}

trait InterfaceScannerTest {
    import InterfaceScannerTest._

    val scanner: InterfaceScanner
    val logger: Logger

    def stop(): Unit = {
        scanner.close()
    }

    def newLinkTest: Test = {
        val desc = """Creating a new link triggers the notification
                   """.stripMargin.replaceAll("\n", " ")
        val promise = Promise()

        val subscription = scanner.subscribe(new Observer[InterfaceOp] {
            override def onCompleted(): Unit =
                promise.tryFailure(new Exception("Did not get notified about new link"))
            override def onError(t: Throwable): Unit =
                promise.tryFailure(t)
            override def onNext(itfOp: InterfaceOp): Unit =
                itfOp match {
                    case InterfaceUpdated(itf) if itf.getName == TestIfName =>
                        promise.tryComplete(null)
                    case _ =>
                }
        })

        val tap = new TapWrapper(TestIfName, true)
        tap.up()

        promise.future.onComplete { case _ =>
            tap.down()
            tap.remove()
            subscription.unsubscribe()
        }

        (desc, promise.future)
    }

    val NewLinkTest: LazyTest = () => newLinkTest

    def delLinkTest: Test = {
        val desc = """Deleting a new link triggers the notification
                   """.stripMargin.replaceAll("\n", " ")
        val promise = Promise()
        val tap = new TapWrapper(TestIfName, true)
        tap.up()
        tap.down()

        val subscription = scanner.subscribe(new Observer[InterfaceOp] {
            override def onCompleted(): Unit =
                promise.tryFailure(new Exception("Did not get notified about link removal"))
            override def onError(t: Throwable): Unit =
                promise.tryFailure(t)
            override def onNext(itfOp: InterfaceOp): Unit =
                itfOp match {
                    case InterfaceDeleted(itf) if itf.getName == TestIfName =>
                        promise.tryComplete(null)
                    case _ =>
                }
        })

        tap.remove()

        promise.future.onComplete { case _ =>
            subscription.unsubscribe()
        }

        (desc, promise.future)
    }

    val DelLinkTest: LazyTest = () => delLinkTest

    def newAddrTest: Test = {
        val desc = """Creating a new addr triggers the notification
                   """.stripMargin.replaceAll("\n", " ")
        val promise = Promise()
        val tap = new TapWrapper(TestIfName, true)
        tap.up()

        val subscription = scanner.subscribe(new Observer[InterfaceOp] {
            override def onCompleted(): Unit =
                promise.tryFailure(new Exception("Did not get notified about new address"))
            override def onError(t: Throwable): Unit =
                promise.tryFailure(t)
            override def onNext(itfOp: InterfaceOp): Unit =
                itfOp match {
                    case InterfaceUpdated(itf) if itf.getName == TestIfName =>
                        if (itf.getInetAddresses.exists(
                                IPv4Addr.fromString(TestIpAddr).equalsInetAddress))
                            promise.tryComplete(null)
                    case _ =>
                }
        })

        if (s"ip a add $TestIpAddr dev $TestIfName".! != 0) {
            promise.failure(TestPrepareException)
        }

        promise.future.onComplete { case _ =>
            s"ip address flush dev $TestIfName".!
            tap.down()
            tap.remove()
            subscription.unsubscribe()
        }

        (desc, promise.future)
    }
    val NewAddrTest: LazyTest = () => newAddrTest

    def delAddrTest: Test = {
        val desc = """Deleting a new addr triggers the notification
                   """.stripMargin.replaceAll("\n", " ")
        val promise = Promise()
        val tap = new TapWrapper(TestIfName, true)
        tap.up()

        if (s"ip a add $TestIpAddr dev $TestIfName".! != 0) {
            promise.failure(TestPrepareException)
        }

        var sawAddress = false
        val subscription = scanner.subscribe(new Observer[InterfaceOp] {
            override def onCompleted(): Unit =
                promise.tryFailure(new Exception("Did not get notified about new address"))
            override def onError(t: Throwable): Unit =
                promise.tryFailure(t)
            override def onNext(itfOp: InterfaceOp): Unit =
                itfOp match {
                    case InterfaceUpdated(itf) if itf.getName == TestIfName =>
                        if (itf.getInetAddresses.exists(
                                IPv4Addr.fromString(TestIpAddr).equalsInetAddress))
                            sawAddress = true
                        else if (sawAddress)
                            promise.tryComplete(null)
                    case _ =>
                }
        })

        if (s"ip address del $TestIpAddr dev $TestIfName".! != 0) {
            promise.tryFailure(TestPrepareException)
        }

        promise.future.andThen { case _ =>
            tap.down()
            tap.remove()
            subscription.unsubscribe()
        }

        (desc, promise.future)
    }
    val DelAddrTest: LazyTest = () => delAddrTest

    def initialSubscriptionTest: Test = {
        val desc = """Subscribing the interface scanner should notify the
                   |current interface descriptions.
                   """.stripMargin.replaceAll("\n", " ")
        val promise = Promise()
        val tap = new TapWrapper(TestIfName, true)
        tap.up()

        val subscription = scanner.subscribe(new Observer[InterfaceOp] {
            override def onCompleted(): Unit =
                promise.tryFailure(new Exception("Did not get notified about new link"))
            override def onError(t: Throwable): Unit =
                promise.tryFailure(t)
            override def onNext(itfOp: InterfaceOp): Unit =
                itfOp match {
                    case InterfaceUpdated(itf) if itf.getName == TestIfName =>
                        promise.tryComplete(null)
                    case _ =>
                }
        })

        promise.future.andThen { case _ =>
            tap.down()
            tap.remove()
            subscription.unsubscribe()
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
    override val scanner = new RtnetlinkInterfaceScanner(new NetlinkChannelFactory)
    override val logger: Logger = Logger(LoggerFactory.getLogger(
        classOf[DefaultInterfaceScannerIntegrationTestBase]))

    def run(): Boolean = {
        var passed = true
        try {
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
