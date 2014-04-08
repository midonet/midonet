/*
 * Copyright (c) 2013 Midokura SARL, All Rights Reserved.
 */
package org.midonet.midolman

import java.util.UUID
import scala.collection.JavaConverters._
import scala.collection.immutable
import scala.collection.mutable.ListBuffer
import scala.concurrent.Promise
import scala.concurrent.promise

import akka.actor.Props
import akka.testkit.TestActorRef
import com.yammer.metrics.core.MetricsRegistry
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest._
import org.scalatest.concurrent.Eventually._

import org.midonet.cache.Cache
import org.midonet.cluster.DataClient
import org.midonet.midolman.DeduplicationActor.ActionsCache
import org.midonet.midolman.PacketWorkflow.Simulation
import org.midonet.midolman.io.DatapathConnectionPool
import org.midonet.midolman.monitoring.metrics.PacketPipelineMetrics
import org.midonet.midolman.services.MessageAccumulator
import org.midonet.midolman.topology.rcu.TraceConditions
import org.midonet.odp.{FlowMatches, Packet, Datapath}
import org.midonet.packets.Ethernet
import org.midonet.packets.util.EthBuilder
import org.midonet.packets.util.PacketBuilder._
import org.midonet.odp.flows.FlowActions.output
import org.midonet.odp.flows.FlowAction

@RunWith(classOf[JUnitRunner])
class DeduplicationActorTestCase extends FeatureSpec
                                 with Matchers with GivenWhenThen
                                 with BeforeAndAfter with MockMidolmanActors
                                 with MidolmanServices
                                 with OneInstancePerTest {

    var datapath: Datapath = null
    var packetsSeen = List[(Packet, Either[Int, UUID])]()
    var ddaRef: TestActorRef[TestableDDA] = _
    def dda = ddaRef.underlyingActor
    var packetsOut = 0

    lazy val dpConnPool = injector.getInstance(classOf[DatapathConnectionPool])
    lazy val metricsReg = injector.getInstance(classOf[MetricsRegistry])

    override def beforeTest() {
        datapath = mockDpConn().futures.datapathsCreate("midonet").get()
        createDda()
    }

    def createDda(simulationExpireMillis: Long = 5000L): Unit = {
        if (ddaRef != null)
            actorSystem.stop(ddaRef)

        val ddaFactory = () => new TestableDDA(new CookieGenerator(1, 1),
            dpConnPool, clusterDataClient(), null, null, null,
            new PacketPipelineMetrics(metricsReg),
            (x: Int) => { packetsOut += x },
            simulationExpireMillis)

        ddaRef = TestActorRef(Props(ddaFactory))(actorSystem)
        dda should not be null
        ddaRef ! DatapathController.DatapathReady(datapath, null)
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
            When("the DDA boots")
            dda should not be null

            Then("the Deduplication should be able to receive conditions lists")
            ddaRef ! TraceConditions(immutable.Seq())
            eventually {
                dda.traceConditions = immutable.Seq()
            }
        }
    }

    feature("DeduplicationActor handles packets") {
        scenario("pends packets that have the same match") {
            Given("four identical packets")
            val pkts = List(makePacket(1), makePacket(1), makePacket(1), makePacket(1))

            When("they are fed to the DDA")
            ddaRef ! DeduplicationActor.HandlePackets(pkts.toArray)

            Then("the DDA should execute exactly one workflow")
            packetsSeen.length should be (1)

            And("exactly one packet should be pended")
            dda.pendedPackets(1) should be (Some(Set(pkts.head)))
        }

        scenario("discards packets when ApplyFlow has no actions") {
            Given("three different packets with the same match")
            val pkts = List(makePacket(1), makeUniquePacket(1), makeUniquePacket(1))

            When("they are fed to the DDA")
            ddaRef ! DeduplicationActor.HandlePackets(pkts.toArray)

            Then("some packets should be pended")
            dda.pendedPackets(1) should not be None

            When("the dda is told to apply the flow with empty actions")
            dda.complete(Nil)

            Then("the packets should be dropped")
            mockDpConn().packetsSent should be (empty)
            dda.pendedPackets(1) should be (None)

            And("packetsOut should be called with the correct number")
            packetsOut should be (3)
        }

        scenario("emits packets when ApplyFlow contains actions") {
            Given("three different packets with the same match")
            val pkts = List(makePacket(1), makeUniquePacket(1), makeUniquePacket(1))

            When("they are fed to the DDA")
            ddaRef ! DeduplicationActor.HandlePackets(pkts.toArray)

            Then("some packets should be pended")
            dda.pendedPackets(1) should not be None

            When("the dda is told to apply the flow with an output action")
            dda.complete(List(output(1)))

            Then("the packets should be sent to the datapath")
            val actual = mockDpConn().packetsSent.asScala.toList.sortBy { _.## }
            val expected = pkts.tail.sortBy { _.## }
            actual should be (expected)

            And("no pended packets should remain")
            dda.pendedPackets(1) should be (None)

            And("packetsOut should be called with the correct number")
            packetsOut should be (3)
        }

        scenario("simulates sequences of packets from the datapath") {
            Given("4 packets with 3 different matches")
            val pkts = List(makePacket(1), makePacket(2), makePacket(3), makePacket(2))

            When("they are fed to the DDA")
            ddaRef ! DeduplicationActor.HandlePackets(pkts.toArray)

            Then("3 packet workflows should be executed")
            val expected = pkts.distinct zip cookieList(1 to 3)
            packetsSeen should be (expected)

            And("one packet should be pended")
            dda.pendedPackets(1) should not be None
            dda.pendedPackets(1).get should be ('empty)
            dda.pendedPackets(2) should not be None
            dda.pendedPackets(2).get should have size 1
            dda.pendedPackets(3) should not be None
            dda.pendedPackets(3).get should be ('empty)
        }

        scenario("simulates generated packets") {
            Given("a packet and a port id")
            val id = UUID.randomUUID()
            val frame: Ethernet = makeFrame(1)

            When("the DDA is told to emit it")
            ddaRef ! DeduplicationActor.EmitGeneratedPacket(id, frame, None)

            Then("a work flow should be executed")
            packetsSeen map {
                case (packet, uuid) => (packet.getPacket, uuid)
            } should be (List((frame, Right(id))))

            And("packetsOut should not be called")
            packetsOut should be (0)
        }

        scenario("expires packets") {
            Given("a pending packet in the DDA")
            createDda(0)
            val pkts = List(makePacket(1), makePacket(1))
            ddaRef ! DeduplicationActor.HandlePackets(pkts.toArray)
            // simulationExpireMillis is 0, pended packets should be
            // expired immediately
            dda.pendedPackets(1) should be (None)

            When("putting another packet handler in the waiting room")
            val pkt2 = makePacket(2)
            ddaRef ! DeduplicationActor.HandlePackets(Array(pkt2))
            dda.pendedPackets(2) should not be (None)
            dda.pendedPackets(2).get should be ('empty)

            And("packetsOut should be called with the correct number")
            packetsOut should be (3)
        }
    }

    class MockPacketHandler(val packet: Packet,
                            val cookieOrEgressPort: Either[Int, UUID],
                            actionsCache: ActionsCache)
            extends PacketHandler {

        val p = promise[Any]

        override val cookieStr = "mock-cookie" + cookieOrEgressPort.toString
        override val cookie = cookieOrEgressPort match {
            case Left(c) => Some(c)
            case Right(_) => None
        }
        override val egressPort = cookieOrEgressPort match {
            case Left(_) => None
            case Right(port) => Some(port)
        }

        var idle = true
        var runs = 0

        override def start() = {
            runs += 1
            if (runs == 1) {
                packetsSeen = packetsSeen :+ (packet, cookieOrEgressPort)
                NotYet(p.future)
            } else {
                Ready(Simulation)
            }
        }

        override def drop() {
            idle = false
        }

        def complete(actions: List[FlowAction]) {
            actionsCache.actions.put(packet.getMatch, actions.asJava)
            p success null
        }
    }

    class TestableDDA(cookieGen: CookieGenerator,
                      dpConnPool: DatapathConnectionPool,
                      clusterDataClient: DataClient,
                      cCache: Cache,
                      tmCache: Cache,
                      tiCache: Cache,
                      metrics: PacketPipelineMetrics,
                      packetOut: Int => Unit,
                      override val simulationExpireMillis: Long)
            extends DeduplicationActor(cookieGen, dpConnPool, clusterDataClient,
                                       cCache, tmCache, tiCache, metrics,
                                       packetOut)
            with MessageAccumulator {

        implicit override val dispatcher = this.context.dispatcher

        val handlers = new ListBuffer[MockPacketHandler]()

        def pendedPackets(cookie: Int): Option[collection.Set[Packet]] =
            cookieToPendedPackets.get(cookie)

        override def workflow(packet: Packet,
                              cookieOrEgressPort: Either[Int, UUID],
                              parentCookie: Option[Int]) = {
            val ph = new MockPacketHandler(packet, cookieOrEgressPort,
                                           actionsCache)
            handlers += ph
            ph
        }

        def complete(actions: List[FlowAction]): Unit = {
            handlers foreach { _.complete(actions) }
            handlers.clear()
        }
    }
}
