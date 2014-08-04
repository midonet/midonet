/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */

package org.midonet.midolman.state

import java.util.{ArrayList, UUID, HashSet => JHashSet, List => JList, Set => JSet}
import scala.collection.JavaConverters._
import scala.collection.mutable

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest._

import org.midonet.cluster.client.{BridgePort, Port}
import org.midonet.midolman.{UnderlayResolver, SoloLogger}
import org.midonet.midolman.simulation.PortGroup
import org.midonet.midolman.state.ConnTrackState.{ConnTrackValue, ConnTrackKey}
import org.midonet.midolman.state.NatState.{NatBinding, NatKey}
import org.midonet.midolman.topology.rcu.Host
import org.midonet.odp.{Packet, Datapath}
import org.midonet.odp.flows.{FlowActions, FlowAction, FlowActionOutput}
import org.midonet.odp.protos.MockOvsDatapathConnection
import org.midonet.packets.{IPAddr, IPv4Addr}
import org.midonet.sdn.state.{IdleExpiration, FlowStateTransaction, FlowStateLifecycle}
import org.midonet.sdn.state.FlowStateTable.Reducer
import org.midonet.sdn.flows.FlowTagger.{FlowTag, FlowStateTag}
import org.midonet.util.functors.{Callback0, Callback2}

@RunWith(classOf[JUnitRunner])
class FlowStateReplicatorTest extends FeatureSpec
                            with BeforeAndAfter
                            with Matchers
                            with OneInstancePerTest
                            with GivenWhenThen {
    implicit def stringToIp(str: String): IPAddr = IPv4Addr.fromString(str)

    type ConnTrackTx = FlowStateTransaction[ConnTrackKey, ConnTrackValue]
    type NatTx = FlowStateTransaction[NatKey, NatBinding]


    var ports = mutable.Map[UUID, Port]()
    var portGroups = mutable.Map[UUID, PortGroup]()

    val ingressHostId = UUID.randomUUID()
    val ingressGroupMemberHostId = UUID.randomUUID()
    val egressHost1 = UUID.randomUUID()
    val egressHost2 = UUID.randomUUID()

    val ingressGroupId = UUID.randomUUID()
    val egressGroupId = UUID.randomUUID()

    def makePort(host: UUID): Port = {
        val p = new BridgePort()
        p.setID(UUID.randomUUID())
        p.hostID = host
        p.portGroups = Set[UUID]()
        p
    }

    def makePort(host: UUID, group: UUID): Port = {
        val p = makePort(host)
        p.portGroups = Set[UUID](group)
        p
    }

    val ingressPortNoGroup = makePort(ingressHostId)
    val egressPortNoGroup = makePort(ingressHostId)

    val ingressPort = makePort(ingressHostId, ingressGroupId)
    val ingressPortGroupMember = makePort(ingressGroupMemberHostId, ingressGroupId)
    val egressPort1 = makePort(egressHost1, egressGroupId)
    val egressPort2 = makePort(egressHost2, egressGroupId)

    val ingressGroup = new PortGroup(ingressGroupId, "ingress", true,
        Set[UUID](ingressPort.id, ingressPortGroupMember.id))
    val egressGroup = new PortGroup(egressGroupId, "egress", true,
        Set[UUID](egressPort1.id, egressPort2.id))

    var sender: TestableFlowStateReplicator = _
    var recipient: TestableFlowStateReplicator = _
    val peers = Map(ingressGroupMemberHostId -> IPv4Addr.fromString("192.168.1.1"),
                    egressHost1 -> IPv4Addr.fromString("192.168.1.2"),
                    egressHost2 -> IPv4Addr.fromString("192.168.1.3"))
    val senderIp = IPv4Addr.fromString("192.168.1.254")

    val senderUnderlay = new MockUnderlayResolver(ingressHostId, senderIp, peers)
    val recipientUnderlay = new MockUnderlayResolver(egressHost1, senderIp, peers)
    val dpConn = new MockOvsDatapathConnection(null)
    var packetsSeen = List[(Packet, List[FlowAction])]()

    var connTrackTx: ConnTrackTx = _
    var natTx: NatTx = _

    val connTrackKeys =
        List(ConnTrackKey("10.0.0.1", 1234, "10.0.0.2", 22, 1, UUID.randomUUID()),
            ConnTrackKey("10.0.0.9", 4578, "10.0.0.12", 80, 2, UUID.randomUUID()))

    val natMappings = Map(
        NatKey(NatKey.FWD_SNAT, "192.168.10.1", 10001, "17.16.15.1", 80, 1, UUID.randomUUID()) ->
               NatBinding("1.2.3.4", 54321),
        NatKey(NatKey.FWD_SNAT, "192.168.10.2", 10002, "17.16.15.2", 443, 2, UUID.randomUUID()) ->
               NatBinding("4.3.2.1", 12345))

    dpConn.packetsExecuteSubscribe(new Callback2[Packet, JList[FlowAction]]() {
        override def call(p: Packet, actions: JList[FlowAction]) {
            val pkt = new Packet(p.getEthernet.clone(), p.getMatch)
            packetsSeen ::= ((pkt, actions.asScala.toList))
        }
    })

    val conntrackDevice = UUID.randomUUID()

    before {
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
        packetsSeen = List.empty
    }

    private def sendAndAcceptTransactions() = {
        sender.accumulateNewKeys(connTrackTx, natTx, ingressPort.id, egressPort1.id,
                                 null, new mutable.HashSet[FlowTag](),
                                 new ArrayList[Callback0])
        sender.pushState(dpConn)
        natTx.commit()
        connTrackTx.commit()
        natTx.flush()
        connTrackTx.flush()
        packetsSeen should not be empty
        val (_, actions) = packetsSeen.head
        acceptPushedState()
        actions
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
            sender.accumulateNewKeys(connTrackTx, natTx, ingressPortNoGroup.id,
                                     egressPortNoGroup.id, null,
                                     new mutable.HashSet[FlowTag](),
                                     new ArrayList[Callback0])
            sender.pushState(dpConn)
            natTx.commit()
            connTrackTx.commit()
            natTx.flush()
            connTrackTx.flush()

            Then("No packets should have been sent")
            packetsSeen should be (empty)

            And("The keys are kept in the replicator")
            for ((k, v) <- natMappings) {
                sender.natTable.get(k) should equal (v)
            }
        }
    }

    feature("Unref callbacks are correctly added") {
        scenario("For conntrack keys") {
            Given("A conntrack key and a contrack ref in a transaction")
            connTrackTx.putAndRef(connTrackKeys.head, ConnTrackState.FORWARD_FLOW)
            connTrackTx.ref(connTrackKeys.drop(1).head)

            val callbacks = new ArrayList[Callback0]

            When("The transaction is commited and added to the replicator")
            connTrackTx.commit()
            sender.accumulateNewKeys(connTrackTx, natTx, ingressPort.id,
                                     egressPort1.id, null,
                                     new mutable.HashSet[FlowTag](), callbacks)

            Then("The unref callbacks should have been correctly added")

            callbacks should have size 2
            (0 to 1) map { connTrackKeys.drop(_).head } foreach { key =>
                sender.conntrackTable.unrefedKeys should not contain (key)
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
            sender.accumulateNewKeys(connTrackTx, natTx, ingressPort.id,
                egressPort1.id, null,
                new mutable.HashSet[FlowTag](), callbacks)

            Then("The unref callbacks should have been correctly added")

            callbacks should have size 2
            (0 to 1) map { natMappings.drop(_).head._1 } foreach { key =>
                sender.natTable.unrefedKeys should not contain (key)
            }

            (0 to 1) foreach { callbacks.get(_).call() }
            (0 to 1) map { natMappings.drop(_).head._1 } foreach { key =>
                sender.natTable.unrefedKeys should contain (key)
            }
        }
    }

    def acceptPushedState() {
        for ((packet, _) <- packetsSeen) {
            recipient.accept(packet.getEthernet)
        }
        packetsSeen = List.empty
    }

    private def preSeedRecipient() {
        for (k <- connTrackKeys) {
            connTrackTx.putAndRef(k, ConnTrackState.RETURN_FLOW)
            sendAndAcceptTransactions()
            recipient.conntrackTable.get(k) should equal (ConnTrackState.RETURN_FLOW)
        }
        for ((k, v) <- natMappings) {
            natTx.putAndRef(k, v)
            sendAndAcceptTransactions()
            recipient.natTable.get(k) should equal (v)
        }
    }

    private def assertRecipientHasAllKeys() {
        for (k <- connTrackKeys) {
            recipient.conntrackTable.get(k) should equal (ConnTrackState.RETURN_FLOW)
        }
        for ((k, v) <- natMappings) {
            recipient.natTable.get(k) should equal (v)
        }
    }

    private def assertRecipientExpiredAllKeys() {
        for (k <- connTrackKeys) {
            recipient.conntrackTable.get(k) should be(null)
        }
        for ((k, _) <- natMappings) {
            recipient.natTable.get(k) should be(null)
        }
    }

    private def assertRecipientUnrefedAllKeys() {
        for (k <- connTrackKeys) {
            recipient.mockConntrackTable.unrefedKeys should contain (k)
        }
        for ((k, _) <- natMappings) {
            recipient.mockNatTable.unrefedKeys should contain (k)
        }
    }

    feature("L4 flow state resolves hosts and ports correctly") {
        scenario("All relevant ingress and egress hosts and ports get detected") {
            val tags = mutable.Set[FlowTag]()

            When("The flow replicator resolves peers for a flow's state")
            val hosts = new JHashSet[UUID]()
            val ports = new JHashSet[UUID]()
            sender.resolvePeers(ingressPort.id, egressPort1.id, null, hosts, ports, tags)

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
            tags should contain (ingressPortGroupMember.deviceTag)
            tags should contain (egressPort1.deviceTag)
            tags should contain (egressPort2.deviceTag)
            tags should contain (ingressGroup.deviceTag)
            tags should contain (egressGroup.deviceTag)
        }
    }

    feature("Flow invalidations triggered by changes in peers' flow state tables") {
        scenario("For connection tracking keys") {
            Given("A conntrack key")
            connTrackTx.putAndRef(connTrackKeys.head, ConnTrackState.RETURN_FLOW)

            When("A host receives it from a peer")
            sendAndAcceptTransactions()

            Then("Flows tagged with it should be invalidated")
            recipient.invalidatedKeys should have length (1)
            recipient.invalidatedKeys should contain (connTrackKeys.head)
        }

        scenario("For nat keys") {
            Given("A nat key")
            val (k,v) = natMappings.head
            natTx.putAndRef(k, v)

            When("A host receives it from a peer")
            sendAndAcceptTransactions()

            Then("Flows tagged with it should be invalidated")
            recipient.invalidatedKeys should have length (1)
            recipient.invalidatedKeys should contain (k)
        }
    }
}

class TestableFlowStateReplicator(
        val ports: mutable.Map[UUID, Port],
        val portGroups: mutable.Map[UUID, PortGroup],
        override val underlay: UnderlayResolver) extends BaseFlowStateReplicator {

    val invalidatedKeys = mutable.MutableList[FlowStateTag]()

    override val log = SoloLogger(this.getClass)

    override val storage = new MockStateStorage()

    val mockConntrackTable = new MockFlowStateTable[ConnTrackKey, ConnTrackValue]()
    override def conntrackTable = mockConntrackTable

    val mockNatTable = new MockFlowStateTable[NatKey, NatBinding]()
    override def natTable = mockNatTable

    override val datapath: Datapath = new Datapath(1, "midonet", null)

    override val invalidateFlowsFor: (FlowStateTag) => Unit = invalidatedKeys.+=

    override def getPort(id: UUID): Port = ports(id)

    override def getPortGroup(id: UUID) = portGroups(id)

    override def getPortSet(id: UUID) = null

    override def resolvePeers(ingressPort: UUID,
                              egressPort: UUID,
                              egressPortSet: UUID,
                              peers: JSet[UUID],
                              ports: JSet[UUID],
                              tags: mutable.Set[FlowTag]) {
        super.resolvePeers(ingressPort, egressPort, egressPortSet, peers, ports, tags)
    }
}

class MockUnderlayResolver(hostId: UUID, hostIp: IPv4Addr,
                           peers: Map[UUID, IPv4Addr]) extends UnderlayResolver {

    import UnderlayResolver.Route

    val output = FlowActions.output(23)

    override def host = Host(hostId, true, 23L, "midonet", Map.empty, Map.empty)

    override def peerTunnelInfo(peer: UUID): Option[Route] = {
        if (peers.contains(peer))
            Some(Route(hostIp.toInt, peers(peer).toInt, output))
        else
            None
    }

    override def vtepTunnellingOutputAction: FlowActionOutput = null
    override def isVtepTunnellingPort(portNumber: Short): Boolean = false
    override def isOverlayTunnellingPort(portNumber: Short): Boolean = false
}

class MockFlowStateTable[K <: IdleExpiration, V]()(implicit ev: Null <:< V)
        extends FlowStateLifecycle[K,V] {

    var entries: Map[K, V] = Map.empty
    var unrefedKeys: Set[K] = Set.empty

    override def expireIdleEntries() {}

    override def expireIdleEntries[U](
        seed: U, func: Reducer[K, V, U]): U = {
        var s = seed
        for ((k, v) <- entries) {
            s = func(s, k, v)
        }
        entries = Map.empty
        s
    }

    override def getRefCount(key: K) = if (unrefedKeys.contains(key)) 0 else 1

    override def unref(key: K) {
        unrefedKeys += key
    }

    override def remove(key: K) = {
        val e = entries
        val oldV = get(key)
        entries -= key
        oldV
    }

    override def fold[U](seed: U, func: Reducer[_ >: K, _ >: V, U]): U = seed

    override def ref(key: K): V = get(key)

    override def get(key: K): V = entries.get(key).orNull

    override def putAndRef(key: K, value: V): V = {
        entries += key -> value
        value
    }

    override def touch(key: K, value: V): Unit = putAndRef(key, value)
}
