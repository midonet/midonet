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

import java.nio.ByteBuffer
import java.util.{ArrayList, Arrays, Collection, HashSet => JHashSet}
import java.util.{Set => JSet, UUID}
import java.util.Random

import scala.collection.JavaConversions._
import scala.collection.mutable
import scala.concurrent.Future

import org.slf4j.LoggerFactory
import org.slf4j.helpers.NOPLogger

import com.google.protobuf.{CodedOutputStream, MessageLite}

import com.typesafe.scalalogging.Logger

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.midolman.{HostRequestProxy, UnderlayResolver}
import org.midonet.midolman.datapath.StatePacketExecutor
import org.midonet.midolman.simulation.PortGroup
import org.midonet.midolman.state.ConnTrackState.{ConnTrackKey, ConnTrackValue}
import org.midonet.midolman.state.NatState.{NatBinding, NatKey}
import org.midonet.midolman.state.TraceState.{TraceKey, TraceContext}
import org.midonet.midolman.simulation.{BridgePort, Port}
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.midolman.util.mock.MockDatapathChannel
import org.midonet.odp.flows.{FlowAction, FlowActionOutput, FlowActions}
import org.midonet.odp.{FlowMatches, Packet}
import org.midonet.packets._
import org.midonet.packets.util.PacketBuilder._
import org.midonet.sdn.flows.FlowTagger.FlowTag
import org.midonet.sdn.state.FlowStateTransaction
import org.midonet.util.FixedArrayOutputStream
import org.midonet.util.functors.Callback0

@RunWith(classOf[JUnitRunner])
class FlowStateReplicatorTest extends MidolmanSpec {
    implicit def stringToIp(str: String): IPv4Addr = IPv4Addr.fromString(str)

    type ConnTrackTx = FlowStateTransaction[ConnTrackKey, ConnTrackValue]
    type NatTx = FlowStateTransaction[NatKey, NatBinding]
    type TraceTx = FlowStateTransaction[TraceKey, TraceContext]

    var ports = mutable.Map[UUID, Port]()
    var portGroups = mutable.Map[UUID, PortGroup]()

    val ingressHostId = UUID.randomUUID()
    val ingressGroupMemberHostId = UUID.randomUUID()
    val egressHost1 = UUID.randomUUID()
    val egressHost2 = UUID.randomUUID()

    val ingressGroupId = UUID.randomUUID()
    val egressGroupId = UUID.randomUUID()

    def makePort(host: UUID): BridgePort =
            BridgePort.random.copy(hostId = host)

    def makePort(host: UUID, group: UUID): BridgePort = {
        makePort(host).copy(portGroups = new ArrayList[UUID]() { add(group) })
    }

    val ingressPortNoGroup = makePort(ingressHostId)
    val egressPortNoGroup = makePort(ingressHostId)

    val ingressPort = makePort(ingressHostId, ingressGroupId)
    val ingressPortGroupMember = makePort(ingressGroupMemberHostId, ingressGroupId)
    val egressPort1 = makePort(egressHost1, egressGroupId)
    val egressPort2 = makePort(egressHost2, egressGroupId)

    val ingressGroup = new PortGroup(ingressGroupId, "ingress", true,
        new ArrayList[UUID]() { add(ingressPort.id); add(ingressPortGroupMember.id) })
    val egressGroup = new PortGroup(egressGroupId, "egress", true,
        new ArrayList[UUID]() { add(egressPort1.id); add(egressPort2.id) })

    var sender: TestableFlowStateReplicator = _
    var recipient: TestableFlowStateReplicator = _
    val peers = Map(ingressGroupMemberHostId -> IPv4Addr.fromString("192.168.1.1"),
                    egressHost1 -> IPv4Addr.fromString("192.168.1.2"),
                    egressHost2 -> IPv4Addr.fromString("192.168.1.3"))
    val senderIp = IPv4Addr.fromString("192.168.1.254")

    val senderUnderlay = new MockUnderlayResolver(ingressHostId, senderIp, peers)
    val recipientUnderlay = new MockUnderlayResolver(egressHost1, senderIp, peers)
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
        NatKey(NatState.FWD_SNAT, "192.168.10.1", 10001, "17.16.15.1", 80, 1, UUID.randomUUID()) ->
               NatBinding("1.2.3.4", 54321),
        NatKey(NatState.FWD_SNAT, "192.168.10.2", 10002, "17.16.15.2", 443, 2, UUID.randomUUID()) ->
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

    override def beforeTest(): Unit = {
        ports += ingressPortNoGroup.id -> ingressPortNoGroup
        ports += egressPortNoGroup.id -> egressPortNoGroup

        ports += ingressPort.id -> ingressPort
        ports += ingressPortGroupMember.id -> ingressPortGroupMember
        ports += egressPort1.id -> egressPort1
        ports += egressPort2.id -> egressPort2

        portGroups += ingressGroup.id -> ingressGroup
        portGroups += egressGroup.id -> egressGroup

        sender = new TestableFlowStateReplicator(ports, portGroups, senderUnderlay)
        recipient = new TestableFlowStateReplicator(ports, portGroups, recipientUnderlay)
        connTrackTx = new ConnTrackTx(sender.conntrackTable)
        natTx = new NatTx(sender.natTable)
        traceTx = new TraceTx(sender.traceTable)
        packetsSeen = List.empty
    }

    private def sendState(ingressPort: UUID, egressPort: UUID,
                          callbacks: ArrayList[Callback0] = new ArrayList[Callback0])
    : (Packet, List[FlowAction]) = {
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
        (packet, context.stateActions.toList)
    }

    private def sendAndAcceptTransactions(): (Packet, List[FlowAction]) = {
        val (packet, actions) = sendState(ingressPort.id, egressPort1.id)
        acceptPushedState(packet)
        (packet, actions)
    }

    feature("State packets serialization") {
        scenario("A (de)serialized packet should be the same as before") {
            import org.midonet.midolman.state.FlowStatePackets._

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

            val len = encoder.encodedLength
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
            val (packet, _) = sendState(ingressPortNoGroup.id, egressPortNoGroup.id)

            Then("No packets should have been sent")
            packet should be (null)

            Then("The keys are kept in the replicator")
            for ((k, v) <- natMappings) {
                sender.natTable.get(k) should equal (v)
            }
        }
    }

    feature("Unref callbacks are correctly added") {
        scenario("For conntrack keys") {
            Given("A conntrack key and a contrack ref in a transaction")
            connTrackTx.putAndRef(connTrackKeys.head, ConnTrackState.RETURN_FLOW)
            connTrackTx.ref(connTrackKeys.drop(1).head)

            val callbacks = new ArrayList[Callback0]

            When("The transaction is commited and added to the replicator")
            sendState(ingressPort.id, egressPort1.id, callbacks)

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
            sendState(ingressPort.id, egressPort1.id, callbacks)

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

    def acceptPushedState(packet: Packet): Unit =
        recipient.accept(packet.getEthernet)

    feature("L4 flow state resolves hosts and ports correctly") {
        scenario("All relevant ingress and egress hosts and ports get detected") {
            val tags = new JHashSet[FlowTag]()

            When("The flow replicator resolves peers for a flow's state")
            val hosts = new JHashSet[UUID]()
            val ports = new JHashSet[UUID]()
            sender.resolvePeers(
                ingressPort.id, new ArrayList[UUID]() { add(egressPort1.id) }, hosts, ports, tags)

            Then("Hosts in the ingress port's port group should be included")
            hosts should contain (ingressGroupMemberHostId)

            And("The host that owns the egress port should be included")
            hosts should contain (egressHost1)

            And("Hosts in the egress port's port group should be included")
            hosts should contain (egressHost2)

            And("All ports should be reported")
            ports should contain (ingressPortGroupMember.id)
            ports should contain (egressPort1.id)
            ports should contain (egressPort2.id)

            hosts should have size 3
            ports should have size 3

            And("The flow should be tagged with all of the ports and port group tags")
            tags should contain (ingressPortGroupMember.flowStateTag)
            tags should contain (egressPort1.flowStateTag)
            tags should contain (egressPort2.flowStateTag)
            tags should contain (ingressGroup.flowStateTag)
            tags should contain (egressGroup.flowStateTag)
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

            val flowState = HostRequestProxy.EmptyFlowStateBatch()
            flowState.strongConnTrack.add(connTrackKeys.head)

            When("Importing it")
            recipient.importFromStorage(flowState)

            Then("Flows tagged with it should be invalidated")
            mockFlowInvalidation should haveInvalidated (connTrackKeys.head)
        }
    }

    class TestableFlowStateReplicator(
            val ports: mutable.Map[UUID, Port],
            val portGroups: mutable.Map[UUID, PortGroup],
            val underlay: UnderlayResolver) extends {
        val conntrackTable = new MockFlowStateTable[ConnTrackKey, ConnTrackValue]()
        val natTable = new MockFlowStateTable[NatKey, NatBinding]()
        val traceTable = new MockFlowStateTable[TraceKey, TraceContext]()
    } with BaseFlowStateReplicator(conntrackTable, natTable, traceTable,
                                   Future.successful(new MockStateStorage),
                                   ingressHostId, underlay,
                                   mockFlowInvalidation,
                                   0) {

        override val log = Logger(LoggerFactory.getLogger(this.getClass))

        override def getPort(id: UUID): Port = ports(id)

        override def getPortGroup(id: UUID) = portGroups(id)

        override def resolvePeers(ingressPort: UUID,
                                  egressPorts: ArrayList[UUID],
                                  peers: JSet[UUID],
                                  ports: JSet[UUID],
                                  tags: Collection[FlowTag]) {
            super.resolvePeers(ingressPort, egressPorts, peers, ports, tags)
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
        override def isVtepTunnellingPort(portNumber: Integer): Boolean = false
        override def isOverlayTunnellingPort(portNumber: Integer): Boolean = false
        override def vxlanRecircOutputAction: FlowActionOutput = null
        override def isVxlanRecircPort(portNumber: Integer): Boolean = false
    }
}
