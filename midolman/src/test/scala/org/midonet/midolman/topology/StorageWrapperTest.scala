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

package org.midonet.midolman.topology

import java.util.UUID
import java.util.concurrent.TimeUnit

import scala.concurrent.Await
import scala.concurrent.duration.FiniteDuration

import org.apache.commons.configuration.HierarchicalConfiguration
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.client.{BridgePort => OldBridgePort, Port => OldPort}
import org.midonet.cluster.data.ports.{BridgePort => OldDataBridgePort}
import org.midonet.cluster.data.storage.Storage
import org.midonet.cluster.data.{Bridge => OldBridge, Ports => OldPorts}
import org.midonet.cluster.models.Topology.{Port => ProtoPort}
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.config.{ConfigGroup, ConfigBool, ConfigProvider}
import org.midonet.midolman.NotYetException
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.topology.devices.{BridgePort => NewBridgePort}
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.midolman.util.mock.MessageAccumulator

import akka.actor.ActorSystem
import akka.testkit.{ImplicitSender, TestKit}

@RunWith(classOf[JUnitRunner])
class StorageWrapperTest extends TestKit(ActorSystem("StorageWrapperTest"))
                         with MidolmanSpec
                         with ImplicitSender {

    private class TestableVTA extends VirtualTopologyActor with MessageAccumulator {}
    private class TestableStorageWrapper(config: MidolmanConfig) extends StorageWrapper(config)
                                                                 with MessageAccumulator {}

    private val oneSecond = new FiniteDuration(1, TimeUnit.SECONDS)

    private var enableNewCluster: Boolean = _
    private var storageWrapper: TestableStorageWrapper = _
    private var vt: VirtualTopology = _
    private implicit var store: Storage = _

    @ConfigGroup(MidolmanConfig.GROUP_NAME)
    trait MidolmanConf extends MidolmanConfig {

        @ConfigBool(key = "enable_newCluster", defaultValue = false)
        def getNewClusterEnabled: Boolean = enableNewCluster
    }

    val conf = ConfigProvider.providerForIniConfig(new HierarchicalConfiguration)
        .getConfig(classOf[MidolmanConf])
    registerActors(VirtualTopologyActor -> (() => new TestableVTA))
    registerActors(StorageWrapper -> (() => new TestableStorageWrapper(conf)))

    override def beforeTest() {
        storageWrapper = StorageWrapper.as[TestableStorageWrapper]
        vt = injector.getInstance(classOf[VirtualTopology])
        store = injector.getInstance(classOf[Storage])
    }

    private def createPort(newCluster: Boolean): Object =
        newCluster match {
            case true =>
                val newPort = ProtoPort.newBuilder()
                    .setId(UUID.randomUUID())
                    .setNetworkId(UUID.randomUUID())
                    .build()
                store.create(newPort)
                newPort
            case false =>
                val bridge = new OldBridge().setName("test")
                bridge.setId(clusterDataClient().bridgesCreate(bridge))
                val oldPort = OldPorts.bridgePort(bridge)
                oldPort.setId(clusterDataClient().portsCreate(oldPort))
    }

    private def updatePort(port: Object): Object = {
        if (port.isInstanceOf[OldDataBridgePort]) {
            val oldPort = port.asInstanceOf[OldDataBridgePort].setInterfaceName("eth0")
            clusterDataClient().portsUpdate(oldPort)
            oldPort
        } else if (port.isInstanceOf[ProtoPort]) {
            val currentPort = port.asInstanceOf[ProtoPort]
            val newPort = ProtoPort.newBuilder()
                .setId(currentPort.getId)
                .setNetworkId(currentPort.getNetworkId)
                .setInterfaceName("eth0")
                .build()
            store.update(newPort)
            newPort
        }
        else
            throw new IllegalArgumentException(s"Unrecognized port class: ${port.getClass}")
    }

    private def getPortId(port: Object): UUID = {
        if (port.isInstanceOf[OldBridgePort])
            port.asInstanceOf[OldBridgePort].id
        else if (port.isInstanceOf[NewBridgePort])
            port.asInstanceOf[NewBridgePort].id
        else if (port.isInstanceOf[OldDataBridgePort])
            port.asInstanceOf[OldDataBridgePort].getId
        else if (port.isInstanceOf[ProtoPort]) {
            port.asInstanceOf[ProtoPort].getId
        }
        else
            throw new IllegalArgumentException(s"Unrecognized port class: ${port.getClass}")
    }

//    feature("The Storage Wrapper returns the requested device") {
//        for (update <- List(true, false)) {
//            for (newCluster <- List(true)) {
//                scenario(s"Device request with new cluster? $newCluster and update? $update") {
//                    Given("A topology with one port")
//                    val port = createPort(newCluster)
//                    val portId = getPortId(port)
//                    enableNewCluster = newCluster
//
//                    println(s"***** cluster enabled: ${conf.getNewClusterEnabled}")
//
//                    When("A Port is requested")
//                    val portReq = PortRequest(portId, update)
//                    storageWrapper.self ! portReq
//
//                    Then("The Storage Wrapper should return the requested port")
//                    val rcvdPort = expectMsgType[OldBridgePort]
//                    rcvdPort.id should be(portId)
//
//                    And("We receive port updates when we are subscribed to the port")
//                    if (update) {
//                        val updatedPort = updatePort(port)
//                        val rcvdPort = expectMsgType[OldBridgePort]
//                        // This does not work with the old cluster
//                        rcvdPort.interfaceName should be("eth0")
//                        storageWrapper.self ! Unsubscribe(portId)
//                    }
//                }
//            }
//        }
//    }

    feature("The Storage Wrapper returns the requested device with TryGet") {
        for (newCluster <- List(false)) {
            scenario(s"Device request with new cluster? $newCluster") {
                Given("A topology with one port")
                val port = createPort(newCluster)
                val portId = getPortId(port)

                When("We ask for the port")
                val nye = intercept[NotYetException] {
                    if (newCluster)
                        storageWrapper.tryGet[NewBridgePort](portId)
                    else
                        storageWrapper.tryGet[OldBridgePort](portId)
                }

                Then("We should receive the Port")
                    newCluster match {
                    case false =>
                        val receivedPort = Await.result(nye.waitFor, oneSecond)
                            .asInstanceOf[OldBridgePort]
                        receivedPort.id should be (portId)
                    case true =>
                        val receivedPort = Await.result(nye.waitFor, oneSecond)
                            .asInstanceOf[NewBridgePort]
                        receivedPort.id should be (portId)
                }
            }
        }
    }
}