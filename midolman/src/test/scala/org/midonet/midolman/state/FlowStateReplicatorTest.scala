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

package org.midonet.midolman.state

import java.net.DatagramSocket
import java.nio.ByteBuffer
import java.util.{ArrayList, Collection, HashSet => JHashSet}
import java.util.{UUID, Set => JSet}
import java.util.Random

import scala.collection.JavaConversions._
import scala.concurrent.Future
import scala.concurrent.duration._

import com.typesafe.config.ConfigFactory

import org.slf4j.helpers.NOPLogger
import com.typesafe.scalalogging.Logger

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.models.Topology._
import org.midonet.cluster.services.MidonetBackend._
import org.midonet.cluster.services.vxgw.FloodingProxyHerald.FloodingProxy
import org.midonet.cluster.topology.TopologyBuilder
import org.midonet.cluster.util.UUIDUtil.{fromProto, toProto}
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.simulation.PacketContext
import org.midonet.midolman.{HostRequestProxy, UnderlayResolver}
import org.midonet.midolman.datapath.StatePacketExecutor
import org.midonet.midolman.state.ConnTrackState.{ConnTrackKey, ConnTrackValue}
import org.midonet.midolman.state.NatState.NatKey
import org.midonet.midolman.state.TraceState.{TraceContext, TraceKey}
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.midolman.util.mock.MockDatapathChannel
import org.midonet.midolman.topology.VirtualTopology
import org.midonet.midolman.topology.devices.{TunnelZone => SimTunnelZone}
import org.midonet.odp.flows.{FlowAction, FlowActionOutput, FlowActions}
import org.midonet.odp.{FlowMatches, Packet}
import org.midonet.packets._
import org.midonet.packets.NatState._
import org.midonet.packets.util.PacketBuilder._
import org.midonet.sdn.flows.FlowTagger
import org.midonet.sdn.flows.FlowTagger.FlowTag
import org.midonet.sdn.state.FlowStateTransaction
import org.midonet.util.functors.Callback0
import org.midonet.util.reactivex._
import org.mockito.Mockito._
import org.mockito.{ArgumentCaptor, Matchers => mockito}

@RunWith(classOf[JUnitRunner])
class FlowStateReplicatorTest extends MidolmanSpec with TopologyBuilder {
    implicit def stringToIp(str: String): IPv4Addr = IPv4Addr.fromString(str)

    type ConnTrackTx = FlowStateTransaction[ConnTrackKey, ConnTrackValue]
    type NatTx = FlowStateTransaction[NatKey, NatBinding]
    type TraceTx = FlowStateTransaction[TraceKey, TraceContext]

    val ingressGroupMemberHostId = UUID.randomUUID()
    val egressHost1Id = UUID.randomUUID()
    val egressHost2Id = UUID.randomUUID()

    val serviceHost1Id = UUID.randomUUID()
    val serviceHost2Id = UUID.randomUUID()

    val ingressGroupId = UUID.randomUUID()
    val egressGroupId = UUID.randomUUID()

    val bridge = Network.newBuilder()
                        .setId(UUID.randomUUID())
                        .build()

    def makeHost(id: UUID) =
        Host.newBuilder()
            .setId(id)
            .build()

    def makePort(host: UUID) =
        Port.newBuilder()
            .setId(UUID.randomUUID())
            .setNetworkId(bridge.getId)
            .setHostId(host)
            .build()

    def makePort(host: UUID, group: UUID) =
        Port.newBuilder()
            .setId(UUID.randomUUID())
            .setNetworkId(bridge.getId)
            .setHostId(host)
            .addPortGroupIds(group)
            .build()

    def makePortGroup(id: UUID, name: String) =
        PortGroup.newBuilder()
                 .setId(id)
                 .setName(name)
                 .setStateful(true)
                 .build()

    val ingressGroupMemberHost = makeHost(ingressGroupMemberHostId)
    val egressHost1 = makeHost(egressHost1Id)
    val egressHost2 = makeHost(egressHost2Id)

    val serviceHost1 = makeHost(serviceHost1Id)
    val serviceHost2 = makeHost(serviceHost2Id)

    var ingressPortNoGroup: Port = _
    var egressPortNoGroup: Port = _

    var ingressPort: Port = _
    val ingressPortGroupMember = makePort(ingressGroupMemberHostId, ingressGroupId)
    val egressPort1 = makePort(egressHost1Id, egressGroupId)
    val egressPort2 = makePort(egressHost2Id, egressGroupId)

    val servicePort1 = makePort(serviceHost1Id)
    val servicePort2 = makePort(serviceHost2Id)

    val ingressGroup = makePortGroup(ingressGroupId, "ingress")
    val egressGroup = makePortGroup(egressGroupId, "egress")

    var sender: TestableFlowStateReplicator = _
    var recipient: TestableFlowStateReplicator = _
    val peers = Map(ingressGroupMemberHostId -> IPv4Addr.fromString("192.168.1.1"),
                    egressHost1Id -> IPv4Addr.fromString("192.168.1.2"),
                    egressHost2Id -> IPv4Addr.fromString("192.168.1.3"))
    val senderIp = IPv4Addr.fromString("192.168.1.254")

    var senderUnderlay: MockUnderlayResolver = _
    val recipientUnderlay = new MockUnderlayResolver(egressHost1Id, senderIp, peers)
    val dpChannel = new MockDatapathChannel()
    var packetsSeen = List[(Packet, List[FlowAction])]()

    val ethernet: Ethernet = { eth src MAC.random() dst MAC.random() }.packet

    implicit var connTrackTx: ConnTrackTx = _
    implicit var natTx: NatTx = _
    implicit var traceTx: TraceTx = _

    val connTrackKeys =
        List(ConnTrackKey("10.0.0.1", 1234, "10.0.0.2", 22, 1, UUID.randomUUID()),
            ConnTrackKey("10.0.0.9", 4578, "10.0.0.12", 80, 2, UUID.randomUUID()))

    val natMappings = Map(
        NatKey(FWD_SNAT, "192.168.10.1", 10001, "17.16.15.1", 80, 1, UUID.randomUUID()) ->
               NatBinding("1.2.3.4", 54321),
        NatKey(FWD_SNAT, "192.168.10.2", 10002, "17.16.15.2", 443, 2, UUID.randomUUID()) ->
            NatBinding("4.3.2.1", 12345))

    val rand = new Random
    val tracePkt1 = { eth src MAC.random() dst MAC.random } <<
        { ip4 src IPv4Addr.random dst IPv4Addr.random } <<
        { udp src 1234 dst 5432 }
    val tracePkt2 = { eth src MAC.random() dst MAC.random } <<
        { ip4 src IPv4Addr.random dst IPv4Addr.random } <<
        { tcp src 1234 dst 5432 }

    val traces = Map(TraceKey.fromFlowMatch(
                         FlowMatches.fromEthernetPacket(tracePkt1))
                         -> new TraceContext(UUID.randomUUID).enable(),
                     TraceKey.fromFlowMatch(
                         FlowMatches.fromEthernetPacket(tracePkt2))
                         -> new TraceContext(UUID.randomUUID).enable())


    val statePacketExecutor = new StatePacketExecutor {
        val log = Logger(NOPLogger.NOP_LOGGER)
    }

    val conntrackDevice = UUID.randomUUID()

    val midolmanConfig = MidolmanConfig.forTests

    val legacyStoreMidolmanConfig = MidolmanConfig.forTests(
        ConfigFactory.parseString(
            s"""
               |agent.minions.flow_state.local_push_state : false
               |agent.minions.flow_state.legacy_push_state : true
            """.stripMargin
        ))

    override def beforeTest(): Unit = {
        ingressPortNoGroup = makePort(hostId)
        egressPortNoGroup = makePort(hostId)

        ingressPort = makePort(hostId, ingressGroupId)

        senderUnderlay = new MockUnderlayResolver(hostId, senderIp, peers)

        val store = injector.getInstance(classOf[VirtualTopology]).store
        store.create(ingressGroupMemberHost)
        store.create(egressHost1)
        store.create(egressHost2)
        store.create(serviceHost1)
        store.create(serviceHost2)
        fetchHosts(hostId, ingressGroupMemberHostId,
                   egressHost1Id, egressHost2Id,
                   serviceHost1Id, serviceHost2Id)

        store.create(ingressGroup)
        store.create(egressGroup)
        fetchPortGroups(ingressGroupId, egressGroupId)

        store.create(bridge)

        store.create(ingressPortNoGroup)
        store.create(egressPortNoGroup)
        store.create(ingressPort)
        store.create(ingressPortGroupMember)
        store.create(egressPort1)
        store.create(egressPort2)
        store.create(servicePort1)
        store.create(servicePort2)
        fetchPorts(ingressPortNoGroup.getId, egressPortNoGroup.getId,
                   ingressPort.getId, ingressPortGroupMember.getId,
                   egressPort1.getId, egressPort2.getId,
                   servicePort1.getId, servicePort2.getId)



        sender = new TestableFlowStateReplicator(senderUnderlay)
        recipient = new TestableFlowStateReplicator(recipientUnderlay)
        connTrackTx = new ConnTrackTx(sender.conntrackTable)
        natTx = new NatTx(sender.natTable)
        traceTx = new TraceTx(sender.traceTable)
        packetsSeen = List.empty
    }

    private def sendState(ingressPort: UUID, egressPort: UUID,
                          callbacks: ArrayList[Callback0] = new ArrayList[Callback0])
    : (Packet, PacketContext) = {
        val context = packetContextFor(ethernet, ingressPort)
        context.flowActions.add(FlowActions.output(1))
        context.outPorts.add(egressPort)

        sender.accumulateNewKeys(context)
        natTx.commit()
        connTrackTx.commit()
        traceTx.commit()

        callbacks.addAll(context.flowRemovedCallbacks)

        val packet = if (context.stateMessageLength > 0) {
            statePacketExecutor.prepareStatePacket(context.stateMessage,
                                                   context.stateMessageLength)
        } else null
        (packet, context)
    }

    private def sendAndAcceptTransactions(): (Packet, List[FlowAction]) = {
        val (packet, context) = sendState(ingressPort.getId, egressPort1.getId)
        acceptPushedState(packet)
        (packet, context.stateActions.toList)
    }

    feature("State packets serialization") {
        scenario("A (de)serialized packet should be the same as before") {
            import FlowStateAgentPackets._

            Given("Flow state packet shell")
            val buffer = new Array[Byte](
                FlowStateEthernet.FLOW_STATE_MAX_PAYLOAD_LENGTH)
            val packet = {
                val udpShell = new FlowStateEthernet(buffer)
                new Packet(udpShell, FlowMatches.fromEthernetPacket(udpShell))
            }

            When("Flow state packet payload is the empty protobuf messsage")
            val encodingBytes = new Array[Byte](
                FlowStateEthernet.FLOW_STATE_MAX_PAYLOAD_LENGTH)
            val encoder = new SbeEncoder()
            val flowStateMessage = encoder.encodeTo(encodingBytes)
            val sender = UUID.randomUUID
            uuidToSbe(sender, flowStateMessage.sender)

            val len = encoder.encodedLength()
            System.arraycopy(encodingBytes, 0, buffer, 0, len)
            val fse = packet.getEthernet.asInstanceOf[FlowStateEthernet]
            fse.limit(len)

            Then("Serialized packet should have the appropriate length")
            val bb = ByteBuffer.allocate(FlowStateEthernet.MTU)
            fse.serialize(bb)
            bb.position should be (len +
                FlowStateEthernet.FLOW_STATE_ETHERNET_OVERHEAD)
            bb.flip()

            And("The deserialized packet should be the same as before")
            val deserialized = Ethernet.deserialize(bb.array())
            val data = deserialized
                .getPayload.getPayload.getPayload.asInstanceOf[Data].getData
            data.length shouldBe len
            ByteBuffer.wrap(data, 0, len) shouldBe ByteBuffer.wrap(encodingBytes, 0, len)
        }
    }

    feature("L4 flow state replication") {
        scenario("Replicates conntrack keys") {
            Given("A conntrack key in a transaction")
            connTrackTx.putAndRef(connTrackKeys.head, ConnTrackState.RETURN_FLOW)

            When("The transaction is added to the replicator and pushed to peers")
            sendAndAcceptTransactions()

            Then("Its peer's stateful tables should contain the key")
            recipient.conntrackTable.get(connTrackKeys.head) should equal (ConnTrackState.RETURN_FLOW)
        }

        scenario("L4 flow state packet size is less than MTU") {
            Given("A conntrack key in a transaction")
            connTrackTx.putAndRef(
                connTrackKeys.head, ConnTrackState.RETURN_FLOW)

            When("A flowstate packet is generated")
            val (packet, _) = sendAndAcceptTransactions()

            Then("Packet size of the flowstate packet should be less than MTU.")
            recipient.conntrackTable.get(
                connTrackKeys.head) should equal (ConnTrackState.RETURN_FLOW)
            val ethernetFrame = packet.getData
            ethernetFrame.length should be < (FlowStateEthernet.MTU -
                FlowStateEthernet.VXLAN_ENCAPUSULATION_OVERHEAD)
        }

        scenario("Replicates Nat keys") {
            Given("A set of nat keys in a transaction")
            for ((k, v) <- natMappings) {
                natTx.putAndRef(k, v)
            }

            When("The transaction is added to the replicator and pushed")
            sendAndAcceptTransactions()

            Then("Its peer's stateful tables should contain the keys")
            for ((k, v) <- natMappings) {
                recipient.natTable.get(k) should equal (v)
            }
        }

        scenario("Keys are owned even if there are no peers") {
            Given("A set of nat keys in a transaction")
            for ((k, v) <- natMappings) {
                natTx.putAndRef(k, v)
            }

            When("The transaction is added to the replicator")
            val (packet, context) = sendState(ingressPortNoGroup.getId, egressPortNoGroup.getId)

            Then("No state packets should have been sent")
            context.stateActions should have size 0

            Then("The keys are kept in the replicator")
            for ((k, v) <- natMappings) {
                sender.natTable.get(k) should equal (v)
            }
        }

        scenario("Keys are always added to context for flow state minion") {
            Given("A set of conntrack and nat keys")
            connTrackTx.putAndRef(
                connTrackKeys.head, ConnTrackState.RETURN_FLOW)
            for ((k,v) <- natMappings) {
                natTx.putAndRef(k, v)
            }

            When("The transaction is added to the replicator")
            val (packet, context) = sendState(ingressPortNoGroup.getId, egressPortNoGroup.getId)

            Then("No state packets should have been sent")
            context.stateActions should have size 0

            And("Context has the message serialized ready for the agent minion")
            context.containsFlowState shouldBe true
            context.stateMessageLength should be > 0
        }

        scenario("Incoming keys are forwarded to the minion in local storage") {
            Given("A conntrack key in a transaction using the local store")
            connTrackTx.putAndRef(connTrackKeys.head, ConnTrackState.RETURN_FLOW)

            When("Sending the state and accepting it on the recipient")
            val (packet, context) = sendState(ingressPort.getId, egressPort1.getId)
            acceptPushedState(packet)

            Then("The flow state is forwarded to the minion")
            verify(recipient.flowStateSocket, times(1)).send(mockito.any())
            recipient.localConfig = midolmanConfig
        }

        scenario("Incoming keys are NOT forwarded to the minion in legacy storage") {
            Given("A conntrack key in a transaction using the legacy store")
            recipient.localConfig = legacyStoreMidolmanConfig
            connTrackTx.putAndRef(connTrackKeys.head, ConnTrackState.RETURN_FLOW)

            When("Sending the state and accepting it on the recipient")
            val (packet, context) = sendState(ingressPort.getId, egressPort1.getId)
            acceptPushedState(packet)

            Then("The flow state is forwarded to the minion")
            verify(recipient.flowStateSocket, times(0)).send(mockito.any())
        }
    }

    feature("Unref callbacks are correctly added") {
        scenario("For conntrack keys") {
            Given("A conntrack key and a contrack ref in a transaction")
            connTrackTx.putAndRef(connTrackKeys.head, ConnTrackState.RETURN_FLOW)
            connTrackTx.ref(connTrackKeys.drop(1).head)

            val callbacks = new ArrayList[Callback0]

            When("The transaction is commited and added to the replicator")
            sendState(ingressPort.getId, egressPort1.getId, callbacks)

            Then("The unref callbacks should have been correctly added")

            callbacks should have size 2
            (0 to 1) map { connTrackKeys.drop(_).head } foreach { key =>
                sender.conntrackTable.unrefedKeys should not contain key
            }

            (0 to 1) foreach { callbacks.get(_).call() }
            (0 to 1) map { connTrackKeys.drop(_).head } foreach { key =>
                sender.conntrackTable.unrefedKeys should contain (key)
            }
        }

        scenario("For nat keys") {
            Given("A nat key and a nat ref() in a transaction")
            natTx.putAndRef(natMappings.head._1, natMappings.head._2)
            val secondNat = natMappings.drop(1).head
            sender.natTable.putAndRef(secondNat._1, secondNat._2)
            natTx.ref(secondNat._1)

            val callbacks = new ArrayList[Callback0]

            When("The transaction is commited and added to the replicator")
            natTx.commit()
            sendState(ingressPort.getId, egressPort1.getId, callbacks)

            Then("The unref callbacks should have been correctly added")

            callbacks should have size 2
            (0 to 1) map { natMappings.drop(_).head._1 } foreach { key =>
                sender.natTable.unrefedKeys should not contain key
            }

            (0 to 1) foreach { callbacks.get(_).call() }
            (0 to 1) map { natMappings.drop(_).head._1 } foreach { key =>
                sender.natTable.unrefedKeys should contain (key)
            }
        }
    }

    def acceptPushedState(packet: Packet): Unit = {
        recipient.accept(packet.getEthernet)
    }

    feature("L4 flow state resolves hosts and ports correctly") {
        scenario("All relevant ingress and egress hosts and ports get detected") {
            val tags = new JHashSet[FlowTag]()

            When("The flow replicator resolves peers for a flow's state")
            val hosts = new JHashSet[UUID]()
            val ports = new JHashSet[UUID]()
            sender.resolvePeers(
                ingressPort.getId, new ArrayList[UUID]() {
                    add(egressPort1.getId)
                }, new ArrayList[UUID](), hosts, ports, tags)

            Then("Hosts in the ingress port's port group should be included")
            hosts should contain (ingressGroupMemberHostId)

            And("The host that owns the egress port should be included")
            hosts should contain (egressHost1Id)

            And("Hosts in the egress port's port group should be included")
            hosts should contain (egressHost2Id)

            And("All ports should be reported")
            ports should contain (fromProto(ingressPortGroupMember.getId))
            ports should contain (fromProto(egressPort1.getId))
            ports should contain (fromProto(egressPort2.getId))

            hosts should have size 3
            ports should have size 3

            And("The flow should be tagged with all of the ports and port group tags")
            tags should contain (FlowTagger.tagForFlowStateDevice(ingressPortGroupMember.getId))
            tags should contain (FlowTagger.tagForFlowStateDevice(egressPort1.getId))
            tags should contain (FlowTagger.tagForFlowStateDevice(egressPort2.getId))
            tags should contain (FlowTagger.tagForFlowStateDevice(ingressGroup.getId))
            tags should contain (FlowTagger.tagForFlowStateDevice(egressGroup.getId))
        }

        scenario ("State is sent to the Flooding Proxy") {
            injector.getInstance(classOf[PeerResolver]).start()
            val vt = injector.getInstance(classOf[VirtualTopology])
            val store = vt.store
            val stateStore = vt.stateStore

            Given("A Flooding Proxy")
            val fpHostId = UUID.randomUUID()
            val fpHost = createHost(id = fpHostId, floodingProxyWeight = 1)
            store.create(fpHost)
            fetchHosts(fpHostId)

            val tzone = createTunnelZone(
                tzType = TunnelZone.Type.VTEP,
                hosts = Map(hostId -> IPv4Addr.random, fpHostId -> IPv4Addr.random))
            store.create(tzone)
            fetchDevice[SimTunnelZone](tzone.getId)

            val fp = FloodingProxy(tzone.getId, fpHostId, IPv4Addr.random)
            stateStore.addValue(
                classOf[TunnelZone],
                tzone.getId,
                FloodingProxyKey,
                fp.toString).await(3 seconds)

            val vxlanPort = createVxLanPort(
                bridgeId = Some(bridge.getId),
                vtepId = Some(UUID.randomUUID()))
            store.create(vxlanPort)
            fetchPorts(vxlanPort.getId)

            When("The flow replicator resolves peers for a flow's state")
            val hosts = new JHashSet[UUID]()
            val ports = new JHashSet[UUID]()
            val tags = new JHashSet[FlowTag]()

            sender.resolvePeers(
                ingressPort.getId, new ArrayList[UUID]() {
                    add(vxlanPort.getId)
                }, new ArrayList[UUID](), hosts, ports, tags)

            Then("Hosts in the ingress port's port group should be included")
            hosts should contain (ingressGroupMemberHostId)

            And("The flooding proxy should be included")
            hosts should contain (fpHostId)

            And("All ports should be reported")
            ports should contain (fromProto(ingressPortGroupMember.getId))
            ports should contain (fromProto(vxlanPort.getId))

            hosts should have size 2
            ports should have size 2

            And("The flow should be tagged with all of the ports and port group tags")
            tags should contain (FlowTagger.tagForFlowStateDevice(ingressPort.getId))
            tags should contain (FlowTagger.tagForFlowStateDevice(ingressPortGroupMember.getId))
            tags should contain (FlowTagger.tagForFlowStateDevice(ingressGroup.getId))
            tags should contain (FlowTagger.tagForFlowStateDevice(vxlanPort.getId))
            tags should have size 4
        }

        scenario("State is sent to service ports") {
            val tags = new JHashSet[FlowTag]()

            When("The flow replicator resolves peers for a flow's state")
            val hosts = new JHashSet[UUID]()
            val ports = new JHashSet[UUID]()
            sender.resolvePeers(
                ingressPort.getId, new ArrayList[UUID](0),
                new ArrayList[UUID]() {
                    add(servicePort1.getId)
                    add(servicePort2.getId)
                }, hosts, ports, tags)

            Then("Hosts in the ingress port's port group should be included")
            hosts should contain (ingressGroupMemberHostId)

            And("The hosts that owns the service ports should be included")
            hosts should contain (serviceHost1Id)
            hosts should contain (serviceHost2Id)
        }
    }

    feature("Flow invalidations triggered by changes in peers' flow state tables") {
        scenario("For connection tracking keys") {
            Given("A conntrack key")
            connTrackTx.putAndRef(connTrackKeys.head, ConnTrackState.RETURN_FLOW)

            When("A host receives it from a peer")
            sendAndAcceptTransactions()

            Then("Flows tagged with it should be invalidated")
            mockFlowInvalidation should haveInvalidated (connTrackKeys.head)
        }

        scenario("For nat keys") {
            Given("A nat key")
            val (k,v) = natMappings.head
            natTx.putAndRef(k, v)

            When("A host receives it from a peer")
            sendAndAcceptTransactions()

            Then("Flows tagged with it should be invalidated")
            mockFlowInvalidation should haveInvalidated (k)
        }
    }

    feature("Importing flow state from storage") {
        scenario("Invalidates flows") {
            Given("A flow state batch")

            val flowState = HostRequestProxy.EmptyFlowStateBatch
            flowState.strongConnTrack.add(connTrackKeys.head)

            val flowState2 = HostRequestProxy.EmptyFlowStateBatch
            flowState2.strongConnTrack.isEmpty shouldBe true

            When("Importing it")
            recipient.importFromStorage(flowState)

            Then("Flows tagged with it should be invalidated")
            mockFlowInvalidation should haveInvalidated (connTrackKeys.head)
        }
    }

    class TestableFlowStateReplicator(
            val underlay: UnderlayResolver) extends {
        val conntrackTable = new MockFlowStateTable[ConnTrackKey, ConnTrackValue]()
        val natTable = new MockFlowStateTable[NatKey, NatBinding]()
        val traceTable = new MockFlowStateTable[TraceKey, TraceContext]()
        var localConfig = midolmanConfig
    } with FlowStateReplicator(conntrackTable, natTable, traceTable,
                               hostId, peerResolver, underlay,
                               mockFlowInvalidation, midolmanConfig) {

        var numIncomingFlowStateMessagesReceived = 0

        override val flowStateSocket = mock(classOf[DatagramSocket])

        override def config = localConfig

        override def resolvePeers(ingressPort: UUID,
                                  egressPorts: ArrayList[UUID],
                                  servicePorts: ArrayList[UUID],
                                  peers: JSet[UUID],
                                  ports: JSet[UUID],
                                  tags: Collection[FlowTag]) {
            super.resolvePeers(ingressPort, egressPorts, servicePorts,
                               peers, ports, tags)
        }
    }

    class MockUnderlayResolver(hostId: UUID, hostIp: IPv4Addr,
                               peers: Map[UUID, IPv4Addr]) extends UnderlayResolver {

        import org.midonet.midolman.UnderlayResolver.Route

        val output = FlowActions.output(23)

        override def peerTunnelInfo(peer: UUID): Option[Route] = {
            if (peers.contains(peer))
                Some(Route(hostIp.toInt, peers(peer).toInt, output))
            else
                None
        }

        override def vtepTunnellingOutputAction: FlowActionOutput = null
        override def isVtepTunnellingPort(portNumber: Int): Boolean = false
        override def isOverlayTunnellingPort(portNumber: Int): Boolean = false
        override def isVppTunnellingPort(portNumber: Int): Boolean = false
        override def tunnelRecircOutputAction: FlowActionOutput = null
        override def hostRecircOutputAction: FlowActionOutput = null
    }
}
