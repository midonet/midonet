/*
 * Copyright 2016 Midokura SARL
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

package org.midonet.midolman.vpp

import java.util.UUID
import java.util.concurrent.ExecutorService

import scala.collection.JavaConverters._
import scala.concurrent.Future

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.slf4j.LoggerFactory

import org.midonet.cluster.data.storage.CreateOp
import org.midonet.cluster.models.Topology.Port
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.topology.TopologyBuilder
import org.midonet.cluster.util.IPSubnetUtil._
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.midolman.topology.{VirtualToPhysicalMapper, VirtualTopology}
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.midolman.vpp.VppExecutor.Receive
import org.midonet.midolman.vpp.VppUplink.{AddUplink, DeleteUplink}
import org.midonet.packets.{IPv6Addr, IPv6Subnet}
import org.midonet.util.concurrent.SameThreadButAfterExecutorService
import org.midonet.util.logging.Logger

@RunWith(classOf[JUnitRunner])
class VppFip64Test extends MidolmanSpec with TopologyBuilder {

    private var backend: MidonetBackend = _
    private var vt: VirtualTopology = _

    private class TestableVppFip64 extends VppExecutor with VppFip64 {
        override def vt = VppFip64Test.this.vt
        override val log = Logger(LoggerFactory.getLogger("vpp-fip64"))
        var messages = List[Any]()

        protected override def newExecutor: ExecutorService = {
            new SameThreadButAfterExecutorService
        }

        protected override def receive: Receive = {
            case m =>
                log debug s"Received message $m"
                messages = messages :+ m
                Future.successful(Unit)
        }

        protected override def doStart(): Unit = {
            start()
            notifyStarted()
        }

        protected override def doStop(): Unit = {
            stop()
            super.doStop()
            notifyStopped()
        }
    }

    protected override def beforeTest(): Unit = {
        vt = injector.getInstance(classOf[VirtualTopology])
        backend = injector.getInstance(classOf[MidonetBackend])
    }

    private def createVppFip64(): TestableVppFip64 = {
        val vppFip64 = new TestableVppFip64
        vppFip64.startAsync().awaitRunning()
        vppFip64
    }

    private def randomSubnet6(): IPv6Subnet = {
        new IPv6Subnet(IPv6Addr.random, 64)
    }

    private def addUplink(port: Port, portIds: UUID*): AddUplink = {
        val portSubnet = port.getPortSubnetList.asScala.map(_.asJava).collect {
            case subnet: IPv6Subnet => subnet
        }.head
        AddUplink(port.getId.asJava, portSubnet, portIds.asJava)
    }

    feature("VPP FIP64 handles uplinks") {
        scenario("Uplink added and removed") {
            Given("A VPP FIP64 instance")
            val vpp = createVppFip64()

            And("A port")
            val router = createRouter()
            val port = createRouterPort(routerId = Some(router.getId.asJava),
                                        portSubnet = randomSubnet6())
            backend.store.multi(Seq(CreateOp(router), CreateOp(port)))

            When("The port becomes active")
            VirtualToPhysicalMapper.setPortActive(port.getId.asJava, 0,
                                                  active = true, 0)

            Then("The controller should add the uplink")
            vpp.messages should contain only addUplink(port, port.getId.asJava)

            When("The port becomes inactive")
            VirtualToPhysicalMapper.setPortActive(port.getId.asJava, 0,
                                                  active = false, 0)

            Then("The controller should delete the uplink")
            vpp.messages should contain theSameElementsInOrderAs Seq(
                addUplink(port, port.getId.asJava),
                DeleteUplink(port.getId.asJava))
        }
    }

}
