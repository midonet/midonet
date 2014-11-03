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
package org.midonet.midolman

import java.util.UUID
import scala.collection.immutable
import scala.concurrent.duration._

import akka.actor.{Actor, Props, ActorRef}
import akka.testkit.TestActorRef
import akka.util.Timeout
import org.junit.runner.RunWith
import org.scalatest._
import org.scalatest.concurrent.Eventually._
import org.scalatest.junit.JUnitRunner

import org.midonet.midolman.DatapathController.DatapathReady
import org.midonet.midolman.PacketsEntryPoint.{Workers, GetWorkers}
import org.midonet.midolman.topology.VirtualTopologyActor
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.midolman.util.mock.MessageAccumulator
import org.midonet.odp.{FlowMatches, Packet, Datapath}
import org.midonet.packets.Ethernet
import org.midonet.packets.util.EthBuilder
import org.midonet.packets.util.PacketBuilder._

@RunWith(classOf[JUnitRunner])
class PacketsEntryPointTestCase extends MidolmanSpec {
    var datapath: Datapath = null
    var packetsSeen = List[(Packet, Either[Int, UUID])]()
    var testablePep: TestablePEP = _

    registerActors(PacketsEntryPoint -> (() => new TestablePEP))

    override def beforeTest() {
        datapath = mockDpConn().futures.datapathsCreate("midonet").get()
        testablePep = PacketsEntryPoint.as[TestablePEP]
        PacketsEntryPoint ! DatapathController.DatapathReady(datapath, null)
    }

    def makeFrame(variation: Short) =
        { eth addr "01:02:03:04:05:06" -> "10:20:30:40:50:60" } <<
        { ip4 addr "192.168.0.1" --> "192.168.0.2" } <<
        { udp ports 10101 ---> variation }

    implicit def ethBuilder2Packet(ethBuilder: EthBuilder): Packet = {
        val frame: Ethernet = ethBuilder
        new Packet(frame, FlowMatches.fromEthernetPacket(frame))
              .setReason(Packet.Reason.FlowTableMiss)
    }

    feature("PacketsEntryPoint initializes correctly") {
        scenario("replies to workers requests") {
            When("the PEP gets asked for the list of workers")
            implicit val timeout: Timeout = 3 seconds
            var workers: Workers = null
            (PacketsEntryPoint ? GetWorkers).mapTo[Workers].onSuccess {
                case reply => workers = reply
            }

            Then("the PEP should reply with the list of workers")
            eventually {
                workers should not be null
            }
            workers.list.length should equal (testablePep.NUM_WORKERS)
        }
    }

    feature("PacketsEntryPoint forwards messages") {
        scenario("forwards DatapathReady msgs") {
            When("the DpC sends a DatapathReady msg")
            testablePep.children foreach { _.getAndClear() }
            val msg = DatapathReady(datapath, null)
            PacketsEntryPoint ! msg

            Then("all the PEP's children should receive the list")
            eventually {
                for (child <- testablePep.children) {
                    child.messages should equal (List(msg))
                }
            }
        }

        scenario("forwards EmitGeneratedPacket msgs") {
            Given("a packet and a port id")
            val id = UUID.randomUUID()
            val frame: Ethernet = makeFrame(1)

            When("the PEP is told to emit it")
            val msg = DeduplicationActor.EmitGeneratedPacket(id, frame, None)
            testablePep.children foreach { _.getAndClear() }
            PacketsEntryPoint ! msg

            Then("a child should receive the request")
            testablePep.children.map(_.getAndClear()).flatten should equal (List(msg))
        }
    }

    object TestablePEP {
        class Child extends Actor {
            override def receive: Actor.Receive = {
                case _ =>
            }
        }
    }

    class TestablePEP extends PacketsEntryPoint with MessageAccumulator {
        import TestablePEP._

        var children = immutable.IndexedSeq[MessageAccumulator]()

        override def NUM_WORKERS = 2

        override def startWorker(index: Int): ActorRef = {
            val props = Props(new Child() with MessageAccumulator)
            val ref = TestActorRef(props)(actorSystem)
            children +:= ref.underlyingActor
            ref
        }
    }
}
