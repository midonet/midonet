/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.odp

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

import org.midonet.odp.protos.OvsDatapathConnection
import org.midonet.odp.ports._
import org.midonet.odp.flows._
import org.midonet.util.IntegrationTests

object OvsIntegrationTest extends DatapathTest with FlowTest with PortTest {

    import IntegrationTests._

    val con = new OvsConnectionOps(DatapathClient.createConnection())

    def main(args: Array[String]) {
        var status = true
        val (dpF, dps, tests) = datapathTests()

        status = printReport(runSuite(tests))

        status &= printReport(runSuite(flowTests(dpF)))

        status &= printReport(runSuite(dpPortTests(dpF)))

        status &= printReport(runSuite(dpCleanup(dps)))

        System exit (if (status) 0 else 1) // necessary for closing con
    }

}

trait DatapathTest {

    val con: OvsConnectionOps

    val dpname1 = "ovsdp-foo"
    val dpname2 = "ovsdp-bar"
    val dpname3 = "ovsdp-baz"

    def datapathTests() = {
        val dpF1 = con ensureDp dpname1
        val dpF2 = dpF1 flatMap { case _ => con ensureDp dpname2 }
        val enum = dpF2 flatMap { case _ => con enumDps() }
        val dpGet1 = enum flatMap { case _ => con getDp dpname1 }

        val dps = Seq[(String,Future[Datapath])]((dpname1,dpF1),(dpname2,dpF2))
        val tests = Seq[(String, Future[Any])](
            ("can create a datapath", dpF1),
            ("can create several datapath", dpF2),
            ("can list existing datapaths", enum),
            ("can get a datapath", dpGet1)
        )

        (dpF1, dps, tests)
    }

    def dpCleanup(dps: Seq[(String,Future[Datapath])]) = dps match {
        case Nil => Nil
        case (name, dp) :: tail =>
            val delDp = dp flatMap { case _ => con delDp name }
            Seq[(String, Future[Any])](
                ("can delete a datapath", delDp),
                ("can delete all datapaths", delDps(tail))
            )
    }

    def delDps(ports: Seq[(String,Future[Datapath])]): Future[String] =
        ports match {
            case Nil => Future.successful("done")
            case (name,dpF) :: tail =>
                for (dp <- dpF; d <- con delDp name; s <- delDps(tail)) yield s
        }

}

trait FlowTest {

    val con: OvsConnectionOps

    def flowTests(dpF: Future[Datapath]) = {

        val mac = Array[Byte](0,1,2,3,4,5)
        val ethK = FlowKeys.ethernet(mac,mac)

        def flow(fm: FlowMatch) = new Flow setMatch fm
        def flowmatch(k: FlowKey) = (new FlowMatch) addKey(k) addKey(ethK)

        def createFlow(f: Flow) = dpF flatMap { con createFlow (f, _) }
        def delFlow(f: Flow) = dpF flatMap { con delFlow (f, _) }

        val flows = (1 to 4) map(FlowKeys.inPort) map(flowmatch) map(flow)

        val makeF = createFlow(flows.head)

        val makesF = Future sequence (flows.tail map createFlow)

        val enumF1 = for {
            dp <- dpF
            f <- makesF
            fs <- con enumFlows dp
        } yield fs

        val delF = makeF flatMap delFlow

        val flushF = delF flatMap { case _ => dpF flatMap { con flushFlows _} }

        val enumF2 = for {
            dp <- dpF
            f <- flushF
            fs <- con enumFlows dp
        } yield {
            if (fs.nonEmpty) throw new Exception("flows remaining")
            true
        }

        Seq[(String, Future[Any])](
            ("can create a flow", makeF),
            ("can create several flows", makesF),
            ("can list flows", enumF1),
            ("can delete a flow", delF),
            ("can flush flows", enumF2)
        )

    }

}

trait PortTest {

    val con: OvsConnectionOps

    val portname1 = "ovstest-foo"
    val portname2 = "ovstest-bar"
    val portname3 = "ovstest-baz"

    def dpPortTests(dpF: Future[Datapath]) = {

        def createNetDev(name: String) =
            dpF flatMap { con ensureNetDevPort(name, _) }

        val ports = Seq(portname1, portname2, portname3) map createNetDev
        val portsF = Future sequence ports

        val getPort = portsF flatMap {
            case _ =>
                dpF flatMap { con getPort(portname1, _) }
        }

        val enum = for {
            dp <- dpF
            netdevPorts <- portsF
            ports <- con enumPorts dp
        } yield {
            if (ports.size < netdevPorts.size)
                throw new Exception("missing ports")
            true
        }

        tunnelTest(dpF) ++ Seq[(String, Future[Any])](
            ("can create a netdev port", ports.head),
            ("can create several port", portsF),
            ("can get a netdev port", getPort),
            ("can list existing ports", enum)
        ) ++ portCleanup((enum flatMap { case _ => dpF}), ports)
    }

    def tunnelTest(dpF: Future[Datapath]) = {
        val makeGre =
            dpF flatMap { con createPort(GreTunnelPort.make("gre"), _) }
        val delGre =
            dpF flatMap { case dp => makeGre flatMap { con delPort(_, dp) } }
        val makeVxLan =
            dpF flatMap { con createPort(VxLanTunnelPort.make("vxlan"), _) }
        val delVxLan =
            dpF flatMap { case dp => makeVxLan flatMap { con delPort(_, dp) } }
        Seq[(String, Future[Any])](
            ("can create a gre tunnel port", makeGre),
            ("can delete a gre tunnel port", delGre),
            ("can create a vxlan tunnel port", makeVxLan),
            ("can delete a vxlan tunnel port", delVxLan)
        )
    }

    def portCleanup(dpF: Future[Datapath], ports: Seq[Future[DpPort]]) =
        ports match {
            case Nil => Nil
            case portF :: tail =>
                Seq[(String, Future[Any])](
                    ("can delete a port", dpF flatMap { delPort(_, portF) }),
                    ("can delete all ports", dpF flatMap { delPorts(_, tail) })
                )
        }

    def delPort(dp: Datapath, portF: Future[DpPort]) =
        portF flatMap { con delPort(_, dp) }

    def delPorts(dp: Datapath, ports: Seq[Future[DpPort]]): Future[String] =
        ports match {
            case Nil => Future.successful("done")
            case port :: tail =>
                delPort(dp, port) flatMap { case _ => delPorts(dp, tail) }
        }
}
