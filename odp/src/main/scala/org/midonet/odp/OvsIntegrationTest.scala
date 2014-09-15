/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.odp.test

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

import org.midonet.odp._
import org.midonet.odp.protos.OvsDatapathConnection

object OvsIntegrationTest extends OvsIntegrationTestBase {

    val baseConnection = DatapathClient.createConnection()
}

trait OvsIntegrationTestBase extends DatapathTest
                                     with FlowTest
                                     with FlowMatchesTcpHeadersTest
                                     with WildcardFlowTest
                                     with PortTest {

    import org.midonet.util.IntegrationTests._

    val baseConnection: OvsDatapathConnection

    var _con: OvsConnectionOps = _

    def con = _con

    def run(): Boolean = {
        _con = new OvsConnectionOps(baseConnection)
        var passed = true

        val (dpF, dps, dpTests) = datapathTests()

        passed = printReport(runSuite(dpTests))

        passed &= printReport(runSuite(flowTests(dpF)))

        passed &= printReport(runSuite(wflowTests(dpF)))

        passed &= printReport(runSuite(tcpFlagsMatchesTests(dpF)))

        passed &= printReport(runSuite(dpPortTests(dpF)))

        passed &= printReport(runSuite(dpCleanup(dps)))

        passed
    }

    def main(args: Array[String]) {
        val status = run()
        System exit (if (status) { 0 } else { 1 }) // necessary for closing con
    }
}

trait DatapathTest {

    def con: OvsConnectionOps

    val dpname1 = "ovsdp-foo"
    val dpname2 = "ovsdp-bar"
    val dpname3 = "ovsdp-baz"

    def datapathTests() = {
        val dpF1 = con ensureDp dpname1
        val dpF2 = dpF1 flatMap { case _ => con ensureDp dpname2}
        val enum = dpF2 flatMap { case _ => con enumDps()}
        val dpGet1 = enum flatMap { case _ => con getDp dpname1}

        val dps = Seq[(String, Future[Datapath])]((dpname1, dpF1),
            (dpname2, dpF2))
        val tests = Seq[(String, Future[Any])](
            ("can create a datapath", dpF1),
            ("can create several datapath", dpF2),
            ("can list existing datapaths", enum),
            ("can get a datapath", dpGet1)
        )

        (dpF1, dps, tests)
    }

    def dpCleanup(dps: Seq[(String, Future[Datapath])]) = dps match {
        case Nil => Nil
        case (name, dp) :: tail =>
            val delDp = dp flatMap { case _ => con delDp name}
            Seq[(String, Future[Any])](
                ("can delete a datapath", delDp),
                ("can delete all datapaths", delDps(tail))
            )
    }

    def delDps(ports: Seq[(String, Future[Datapath])]): Future[String] =
        ports match {
            case Nil => Future.successful("done")
            case (name, dpF) :: tail =>
                for (dp <- dpF; d <- con delDp name; s <- delDps(tail)) yield { s }
        }
}
