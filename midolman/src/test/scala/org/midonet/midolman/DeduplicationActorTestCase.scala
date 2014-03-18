/*
* Copyright 2013 Midokura Europe SARL
*/
package org.midonet.midolman

import java.util.UUID
import scala.collection.JavaConverters._
import scala.collection.immutable
import scala.collection.mutable.ListBuffer
import scala.concurrent.Promise
import scala.concurrent.promise
import scala.concurrent.duration._

import akka.actor.Props
import akka.testkit.TestActorRef
import com.yammer.metrics.core.MetricsRegistry
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest._
import org.scalatest.concurrent.Eventually._

import org.midonet.cache.Cache
import org.midonet.cluster.DataClient
import org.midonet.midolman.DeduplicationActor.ApplyFlow
import org.midonet.midolman.PacketWorkflow.Simulation
import org.midonet.midolman.io.DatapathConnectionPool
import org.midonet.midolman.monitoring.metrics.PacketPipelineMetrics
import org.midonet.midolman.services.MessageAccumulator
import org.midonet.midolman.topology.rcu.TraceConditions
import org.midonet.odp.{FlowMatches, Packet, Datapath}
import org.midonet.odp.flows.FlowActions.output
import org.midonet.packets.Ethernet
import org.midonet.packets.util.EthBuilder
import org.midonet.packets.util.PacketBuilder._

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

    lazy val dpConnPool = injector.getInstance(classOf[DatapathConnectionPool])
    lazy val metricsReg = injector.getInstance(classOf[MetricsRegistry])

    override def beforeTest() {
        datapath = mockDpConn().futures.datapathsCreate("midonet").get()

        val ddaFactory = () => new TestableDDA(new CookieGenerator(1, 1),
            dpConnPool, clusterDataClient(), null, null, null,
            new PacketPipelineMetrics(metricsReg))

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

            Then("and exactly one packet should be pended")
            dda.pendedPackets(1) should be (Some(Set(pkts.head)))
        }

        scenario("discards packets When ApplyFlow has no actions") {
            Given("three different packets with the same match")
            val pkts = List(makePacket(1), makeUniquePacket(1), makeUniquePacket(1))

            When("they are fed to the DDA")
            ddaRef ! DeduplicationActor.HandlePackets(pkts.toArray)

            Then("some packets should be pended")
            dda.pendedPackets(1) should not be None

            When("the dda is told to apply the flow with empty actions")
            MockPacketHandler.complete()
            ddaRef ! ApplyFlow(Nil, Some(1))

            Then("the packets should be dropped")
            mockDpConn().packetsSent.size should be (0)
            dda.pendedPackets(1) should be (None)
        }

        scenario("emits packets When ApplyFlow contains actions") {
            Given("three different packets with the same match")
            val pkts = List(makePacket(1), makeUniquePacket(1), makeUniquePacket(1))

            When("they are fed to the DDA")
            ddaRef ! DeduplicationActor.HandlePackets(pkts.toArray)

            Then("some packets should be pended")
            dda.pendedPackets(1) should not be None

            When("the dda is told to apply the flow with an output action")
            ddaRef ! ApplyFlow(List(output(1)), Some(1))
            MockPacketHandler.complete()

            Then("the packets should be sent to the datapath")
            val actual = mockDpConn().packetsSent.asScala.toList.sortBy { _.## }
            val expected = pkts.tail.sortBy { _.## }
            actual should be (expected)

            And("no pended packets should remain")
            dda.pendedPackets(1) should be (None)
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
        }
    }

    object MockPacketHandler {
        val futures: ListBuffer[Promise[PacketWorkflow.PipelinePath]] = new ListBuffer()

        def complete() {
            futures foreach {
                f => f.success(Simulation)
            }
            futures.clear()
        }
    }

    class MockPacketHandler(val packet: Packet,
            val cookieOrEgressPort: Either[Int, UUID]) extends PacketHandler {

        override val cookieStr = "mock-cookie" + cookieOrEgressPort.toString
        override val cookie = cookieOrEgressPort match {
            case Left(cookie) => Some(cookie)
            case Right(_) => None
        }
        override val egressPort = cookieOrEgressPort match {
            case Left(_) => None
            case Right(port) => Some(port)
        }

        override def start() = {
            packetsSeen = packetsSeen :+ (packet, cookieOrEgressPort)
            val p = promise[PacketWorkflow.PipelinePath]()
            MockPacketHandler.futures.append(p)
            p.future
        }
    }

    class TestableDDA(cookieGen: CookieGenerator,
                      dpConnPool: DatapathConnectionPool,
                      clusterDataClient: DataClient,
                      cCache: Cache,
                      tmCache: Cache,
                      tiCache: Cache,
                      metrics: PacketPipelineMetrics) extends
              DeduplicationActor(cookieGen, dpConnPool, clusterDataClient,
                cCache, tmCache, tiCache, metrics) with MessageAccumulator {
        protected override val cookieTimeToLiveMillis = 300L
        protected override val cookieExpirationCheckInterval = 100 millis

        implicit override val dispatcher = this.context.dispatcher

        def pendedPackets(cookie: Int): Option[collection.Set[Packet]] =
            cookieToPendedPackets.get(cookie)

        override def workflow(packet: Packet,
                              cookieOrEgressPort: Either[Int, UUID],
                              parentCookie: Option[Int]) =
            new MockPacketHandler(packet, cookieOrEgressPort)
    }
}
