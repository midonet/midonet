/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.odp

import scala.collection.JavaConversions.asScalaSet
import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.util.{Try, Success, Failure}

import org.midonet.odp.protos.OvsDatapathConnection
import org.midonet.odp.ports.NetDevPort

object OvsIntegrationTest {

    val dpname1 = "ovstest-foo"
    val dpname2 = "ovstest-bar"

    val portname1 = "porttest-foo"
    val portname2 = "porttest-bar"
    val portname3 = "porttest-baz"

    def main(args: Array[String]) {
        val con = new OvsConnectionOps(DatapathClient.createConnection())

        val dpFut1 = makeDp(con, dpname1)

        val portTest = dpFut1 flatMap { PortsTest.run(con, _) }

        val dpFut2 = portTest flatMap { case _ => makeDp(con,dpname2) }

        val enum = dpFut2 flatMap { case _ => checkDpEnums(con) { _.size == 2} }

        val done = for {
          e <- enum
          dp1 <- dpFut1 flatMap delDp(con, dpname1)
          dp2 <- dpFut2 flatMap delDp(con, dpname2)
        } yield { true }

        Await.result(done, 3 seconds)
        System exit 0
    }

    def checkDpEnums(con: OvsConnectionOps)
                    (test: Set[Datapath] => Boolean) = con enumDps() map {
        case dps: Set[Datapath] =>
            println("listing dps")
            assert { test(dps filter { _.getName contains "ovstest" }) }
            true
    }

    def makeDp(con: OvsConnectionOps, name: String) =
        con ensureDp name map { case dp => println("got " + dp); dp }

    def delDp(con: OvsConnectionOps, name: String): Any => Future[Datapath] =
        { case any: Any => println("deletting dp " + name); con delDp name }

    class PortsTest(val con: OvsConnectionOps, val dp: Datapath) {

        import PortsTest._

        def checkEnums(test: Set[DpPort] => Boolean) = con enumPorts(dp) map {
            case ports =>
                assert { test(netdevPorts(ports)) }
                true
        }

        def continuePort(name: String): Any => Future[DpPort] =
            { case any: Any => con.ensureNetDevPort(portname1, dp) }

    }

    object PortsTest {

        def netdevPorts(ports: Set[DpPort]) =
            ports filter { _.isInstanceOf[NetDevPort] }

        def run(con: OvsConnectionOps, dp: Datapath) = {
            val portTest = new PortsTest(con, dp)
            (portTest checkEnums { s: Set[DpPort] => s.size == 0 })
              .map{ case _ => true}
              //.flatMap(portTest continuePort portname1)
              //.flatMap(portTest continuePort portname2)
              //.flatMap(portTest continuePort portname2)
              //.flatMap(portTest continuePort portname3)
              //.flatMap { case x => portTest checkEnums { _.size == 3} }
        }
    }
}
