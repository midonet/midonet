/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.midolman.io

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent._
import scala.concurrent.duration._
import scala.util.{Failure, Success}

import akka.actor.{Actor, ActorRef, ActorSystem, Props}

import org.midonet.config.ConfigProvider;
import org.midonet.midolman.NetlinkCallbackDispatcher
import org.midonet.midolman.PacketsEntryPoint
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.odp._
import org.midonet.odp.ports._
import org.midonet.odp.protos.OvsDatapathConnection
import org.midonet.util._

object UpcallChannelsTest {

    import IntegrationTests._
    import PacketsEntryPoint.Workers

    class Deaf extends Actor {
        def receive = { case _ => }
    }

    trait TestMixin { self: UpcallDatapathConnectionManagerBase  =>
        val dispatcher: ActorRef
        val packetHandler: ActorRef
        override def askForWorkers()(implicit ec: ExecutionContext,
                                              as: ActorSystem) =
            Future successful Workers(Vector(packetHandler))
        override def getDispatcher()(implicit as: ActorSystem) =
            NetlinkCallbackDispatcher.makeBatchCollector(Some(dispatcher))(as)
    }

    def tbPolicy(conf: MidolmanConfig) = {
        val counter = new StatisticalCounter(conf.getSimulationThreads)
        new TokenBucketPolicy(conf, new TokenBucketSystemRate(counter), 1,
                              _ => Bucket.BOTTOMLESS)
    }

    def main(args: Array[String]) {

        implicit val sys = ActorSystem("upcallChannelsTest")
        val act = sys actorOf Props[Deaf]
        val nlDispatcher = sys actorOf Props[NetlinkCallbackDispatcher]

        val conf = ConfigProvider.configFromIniFile(args(0), classOf[MidolmanConfig])

        val mngr1 =
            new OneToOneDpConnManager(conf, tbPolicy(conf)) with TestMixin {
                val dispatcher: ActorRef = nlDispatcher
                val packetHandler: ActorRef = act
            }

        val mngr2 =
            new OneToManyDpConnManager(conf, tbPolicy(conf)) with TestMixin {
                val dispatcher: ActorRef = nlDispatcher
                val packetHandler: ActorRef = act
            }

        var status = printReport(runSuite(test("OneToOneDpConnManager", mngr1)))
        status &= printReport(runSuite(test("OneToManyDpConnManager", mngr2)))

        System exit (if (status) 0 else 1) // necessary for closing con
    }

    def test(mngrType: String, mngr: UpcallDatapathConnectionManager)
            (implicit ec: ExecutionContext, as: ActorSystem) = {
        val dpName = "dptest"
        val con = new OvsConnectionOps(DatapathClient.createConnection())
        val dp = Await.result(con.ensureDp(dpName), 2 seconds)

        val name1 = "ovstest-foo"
        val name2 = "ovstest-bar"
        val name3 = "ovstest-baz"
        val ports = List(name1, name2, name3) map { new NetDevPort(_) }

        val portCr8Fut =
            Future.traverse(ports) { mngr createAndHookDpPort(dp, _) }

        val portDelFut = portCr8Fut flatMap {
            case ports =>
                Future.traverse(ports) {
                    case (p, _) =>
                        mngr deleteDpPort(dp, p) }
        }

        val enumPorts = for {
            _ <- portDelFut
            portEnum <- con enumPorts dp
            netdevPorts = portEnum filter{ _.isInstanceOf[NetDevPort] }
        } yield {
            if (netdevPorts.nonEmpty)
                throw new Exception("ports remaining: " + netdevPorts)
            true
        }

        val grePortFut =
            mngr createAndHookDpPort(dp, GreTunnelPort.make("tngre-mm"))

        val grePortDelFut =
            grePortFut flatMap { case (p,_) => mngr deleteDpPort(dp, p) }

        val vxLanPortFut =
            mngr createAndHookDpPort(dp, VxLanTunnelPort.make("tnvxlan-mm"))

        val vxLanPortDelFut =
            vxLanPortFut flatMap { case (p,_) => mngr deleteDpPort(dp, p) }

        Seq[(String, Future[Any])](
            ("can create ports with " + mngrType, portCr8Fut),
            ("can delete ports with " + mngrType, portDelFut),
            ("no netdev port remains in the datapath", enumPorts),
            ("can create a gre tunnel port with" + mngrType, grePortFut),
            ("can delete a gre tunnel port with" + mngrType, grePortDelFut),
            ("can create a vxlan tunnel port with" + mngrType, vxLanPortFut),
            ("can delete a vxlan tunnel port with" + mngrType, vxLanPortDelFut)
        )
    }
}
