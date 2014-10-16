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
package org.midonet.odp.test

import java.util.{ArrayList => JArrayList}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

import org.midonet.odp._
import org.midonet.odp.flows._
import org.midonet.odp.ports._

trait FlowTest {

    def con: OvsConnectionOps

    def flowTests(dpF: Future[Datapath]) = {

        val mac = Array[Byte](0, 1, 2, 3, 4, 5)
        val ethK = FlowKeys.ethernet(mac, mac)

        def flow(fm: FlowMatch) = new Flow(fm, new JArrayList[FlowAction]())
        def flowmatch(k: FlowKey) = (new FlowMatch) addKey (k) addKey (ethK)

        def createFlow(f: Flow) = dpF flatMap { con createFlow(f, _) }
        def delFlow(f: Flow) = dpF flatMap { con delFlow(f, _) }

        val flows = (1 to 4) map (FlowKeys.inPort) map (flowmatch) map (flow)

        val makeF = createFlow(flows.head)

        val makesF = Future sequence (flows.tail map createFlow)

        val enumF1 = for {
            dp <- dpF
            f <- makesF
            fs <- con enumFlows dp
        } yield {
            fs
        }

        val delF = makeF flatMap delFlow

        val flushF = delF flatMap { case _ => dpF flatMap { con flushFlows _ } }

        val enumF2 = for {
            dp <- dpF
            f <- flushF
            fs <- con enumFlows dp
        } yield {
            if (fs.nonEmpty) {
                throw new Exception("flows remaining")
            }
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

    def con: OvsConnectionOps

    val portname1 = "ovstest-foo"
    val portname2 = "ovstest-bar"
    val portname3 = "ovstest-baz"

    def dpPortTests(dpF: Future[Datapath]) = {

        def createNetDev(name: String) = dpF flatMap { con ensureNetDevPort(name, _) }

        val ports = Seq(portname1, portname2, portname3) map createNetDev
        val portsF = Future sequence ports

        val getPort = portsF flatMap { case _ => dpF flatMap { con getPort(portname1, _) } }

        val enum = for {
            dp <- dpF
            netdevPorts <- portsF
            ports <- con enumPorts dp
        } yield {
            if (ports.size < netdevPorts.size) {
                throw new Exception("missing ports")
            }
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
        val makeGre = dpF flatMap {
            con createPort(GreTunnelPort.make("gre"), _)
        }

        val delGre = dpF flatMap { case dp => makeGre flatMap { con delPort(_, dp) } }

        val vxlanPorts = List(
            VxLanTunnelPort.make("vxlan1"),
            VxLanTunnelPort.make("vxlan2", 6677)
        )

        val makeVxLan = dpF flatMap { dp =>
            Future.traverse(vxlanPorts) { con createPort(_, dp) }
        }

        val delVxLan = for (dp <- dpF; ports <- makeVxLan)
        yield {
            Future.traverse(ports) { con delPort(_, dp) }
        }

        Seq[(String, Future[Any])](
            ("can create a gre tunnel port", makeGre),
            ("can delete a gre tunnel port", delGre),
            ("can create two vxlan tunnel ports", makeVxLan),
            ("can delete vxlan tunnel ports", delVxLan)
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
                delPort(dp, port) flatMap { case _ => delPorts(dp, tail)}
        }
}
