/*
* Copyright 2013 Midokura Europe SARL
*/
package org.midonet.midolman

import scala.collection.JavaConverters._
import akka.testkit.TestActorRef
import akka.dispatch.Promise
import java.util.UUID
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.concurrent.Eventually._
import org.scalatest.time.Millis
import org.scalatest.time.Span

import org.midonet.midolman.topology.{TraceConditionsManager, VirtualTopologyActor}
import org.midonet.midolman.topology.VirtualTopologyActor.ConditionListRequest
import org.midonet.odp.{FlowMatches, Packet, Datapath}
import org.midonet.packets.util.PacketBuilder._
import org.midonet.packets.Ethernet
import org.midonet.packets.util.EthBuilder
import org.midonet.midolman.DeduplicationActor.ApplyFlow
import org.midonet.odp.flows.FlowActionOutput


@RunWith(classOf[JUnitRunner])
class DeduplicationActorTestCase extends SingleActorTestCase {

    var dda: TestActorRef[TestableDDA] = null
    var datapath: Datapath = null
    var packetsSeen = List[(Packet, Either[Int, UUID])]()

    override def beforeTest() {
        dda = actorFor(() => new TestableDDA())
        datapath = mockDpConn.datapathsCreate("midonet").get()
        dda.tell(DatapathController.DatapathReady(datapath, null))
    }

    def makeFrame(variation: Short) =
        { eth addr "01:02:03:04:05:06" -> "10:20:30:40:50:60" } <<
        { ip4 addr "192.168.0.1" --> "192.168.0.2" } <<
        { udp ports 10101 ---> variation }

    def makeUniqueFrame(variation: Short) =
        makeFrame(variation) << payload(UUID.randomUUID().toString)

    implicit def ethBuilder2Packet(ethBuilder: EthBuilder): Packet = {
        val frame: Ethernet = ethBuilder
        new Packet().
            setPacket(frame).
            setMatch(FlowMatches.fromEthernetPacket(frame)).
            setReason(Packet.Reason.FlowTableMiss)
    }

    def makePacket(variation: Short): Packet = makeFrame(variation)

    def makeUniquePacket(variation: Short): Packet = makeUniqueFrame(variation)

    def cookieList(cookies: Seq[Int]): List[Either[Int, UUID]] =
        cookies map { case c => Left(c) } toList

    feature("DeduplicationActor initializes correctly") {
        scenario("requests the condition list to the VTA") {
            when("the DDA boots")
            val vtaOpt = actorsService.actor(VirtualTopologyActor.Name)
            vtaOpt should not be None

            then("the VTA must receive a ConditionListRequest")
            vtaOpt foreach { vta =>
                vta.getAndClear should be ===
                    List(ConditionListRequest(TraceConditionsManager.uuid, update = true))
            }
        }
    }

    feature("DeduplicationActor handles packets") {
        scenario("pends packets that have the same match") {
            given("four identical packets")
            val pkts = List(makePacket(1), makePacket(1), makePacket(1), makePacket(1))

            when("they are fed to the DDA")
            dda.tell(DeduplicationActor.HandlePackets(pkts.toArray))

            then("the DDA should execute exactly one workflow")
            packetsSeen.length should be === 1

            then("and exactly one packet should be pended")
            dda.underlyingActor.pendedPackets(1) should be === Some(Set(pkts.head))
        }

        scenario("expires pended packets after a time interval") {
            implicit val patienceConfig =
                PatienceConfig(Span(500, Millis), Span(100, Millis))

            given("three different packets with the same match")
            val pkts = List(makePacket(1), makeUniquePacket(1), makeUniquePacket(1))

            when("they are fed to the DDA")
            dda.tell(DeduplicationActor.HandlePackets(pkts.toArray))

            then("two packets should be pended")
            dda.underlyingActor.pendedPackets(1) should not be None
            dda.underlyingActor.pendedPackets(1).get.size should be === 2

            when("their cookie expires")
            then("they should be unpended automatically")
            eventually {
                dda.underlyingActor.pendedPackets(1) should be === None
            }
            and("not executed")
            mockDpConn.packetsSent.size should be === 0
        }

        scenario("discards packets when ApplyFlow has no actions") {
            given("three different packets with the same match")
            val pkts = List(makePacket(1), makeUniquePacket(1), makeUniquePacket(1))

            when("they are fed to the DDA")
            dda.tell(DeduplicationActor.HandlePackets(pkts.toArray))

            then("some packets should be pended")
            dda.underlyingActor.pendedPackets(1) should not be None

            when("the dda is told to apply the flow with empty actions")
            dda.tell(ApplyFlow(Nil, Some(1)))

            then("the packets should be dropped")
            mockDpConn.packetsSent.size should be === 0
            dda.underlyingActor.pendedPackets(1) should be === None
        }

        scenario("emits packets when ApplyFlow contains actions") {
            given("three different packets with the same match")
            val pkts = List(makePacket(1), makeUniquePacket(1), makeUniquePacket(1))

            when("they are fed to the DDA")
            dda.tell(DeduplicationActor.HandlePackets(pkts.toArray))

            then("some packets should be pended")
            dda.underlyingActor.pendedPackets(1) should not be None

            when("the dda is told to apply the flow with an output action")
            dda.tell(ApplyFlow(List(new FlowActionOutput().setPortNumber(1)), Some(1)))

            then("the packets should be sent to the datapath")
            val actual = mockDpConn.packetsSent.asScala.toList.sortBy { _.## }
            val expected = pkts.tail.sortBy { _.## }
            actual should be === expected

            and("no pended packets should remain")
            dda.underlyingActor.pendedPackets(1) should be === None
        }

        scenario("simulates sequences of packets from the datapath") {
            given("4 packets with 3 different matches")
            val pkts = List(makePacket(1), makePacket(2), makePacket(3), makePacket(2))

            when("they are fed to the DDA")
            dda.tell(DeduplicationActor.HandlePackets(pkts.toArray))

            then("3 packet workflows should be executed")
            val expected = pkts.distinct zip cookieList(1 to 3)
            packetsSeen should be === expected

            and("one packet should be pended")
            dda.underlyingActor.pendedPackets(1) should not be None
            dda.underlyingActor.pendedPackets(1).get should be ('empty)
            dda.underlyingActor.pendedPackets(2) should not be None
            dda.underlyingActor.pendedPackets(2).get should have size (1)
            dda.underlyingActor.pendedPackets(3) should not be None
            dda.underlyingActor.pendedPackets(3).get should be ('empty)
        }

        scenario("simulates packets received through the datapath hook") {
            given("one packet")
            val pkt = makePacket(1)

            when("it's injected through the datapath")
            mockDpConn.triggerPacketIn(pkt)

            then("the DDA should receive it and execute one workflow")
            packetsSeen should be === List((pkt, Left(1)))
        }

        scenario("simulates generated packets") {
            given("a packet and a port id")
            val id = UUID.randomUUID()
            val frame: Ethernet = makeFrame(1)

            when("the DDA is told to emit it")
            dda.tell(DeduplicationActor.EmitGeneratedPacket(id, frame, None))

            then("a work flow should be executed")
            packetsSeen map {
                case (packet, uuid) => (packet.getPacket, uuid)
            } should be === List((frame, Right(id)))
        }
    }

    class MockPacketHandler(val packet: Packet,
            val cookieOrEgressPort: Either[Int, UUID]) extends PacketHandler {

        override def start() = {
            packetsSeen = packetsSeen :+ (packet, cookieOrEgressPort)
            Promise.successful(true)
        }
    }

    class TestableDDA extends DeduplicationActor {
        protected override val cookieTimeToLiveMillis = 300L
        protected override val cookieExpirationCheckIntervalMillis = 100L

        def pendedPackets(cookie: Int): Option[collection.Set[Packet]] =
            cookieToPendedPackets.get(cookie)

        override def workflow(packet: Packet, cookieOrEgressPort: Either[Int, UUID]) =
            new MockPacketHandler(packet, cookieOrEgressPort)
    }
}
