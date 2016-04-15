/*
 * Copyright 2015 Midokura SARL
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

import java.util.{Date, UUID}

import scala.collection.mutable
import scala.concurrent.duration.{Duration, DurationInt}

import org.scalatest.concurrent.Eventually

import org.midonet.cluster.models.Topology.{Router, Port}
import org.midonet.cluster.topology.{TopologyBuilder, TopologyMatchers}
import org.midonet.cluster.util.UUIDUtil.asRichProtoUuid
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.packets.IPv4Addr
import org.midonet.util.reactivex.TestAwaitableObserver

class RouterIPMapperTest extends MidolmanSpec with TopologyBuilder
                                 with TopologyMatchers with Eventually {
    private var vt: VirtualTopology = _
    private val timeout: Duration = 1 second

    override protected def beforeTest(): Unit = {
        vt = injector.getInstance(classOf[VirtualTopology])
    }

    feature("RouterIPMapper") {
        scenario("Publishes IP addresses for existing ports.") {
            val addrs = Seq("10.0.0.1", "10.0.0.2", "10.0.0.3")
                .map(IPv4Addr.fromString)
            val ports = createRouterWithPorts(addrs)
            val obs = createMapperAndObserver(ports.head.getRouterId.asJava)
            obs.awaitOnNext(1, timeout)
            obs.getOnNextEvents.get(0) shouldBe addrs.toSet
        }

        scenario("Publishes updates for port addition and deletion") {
            val addrs = Seq(IPv4Addr("10.0.0.1"), IPv4Addr("10.0.0.2"),
                            IPv4Addr("10.0.0.3"))

            // Create router with two ports.
            val ports = mutable.ListBuffer[Port]()
            ports ++= createRouterWithPorts(addrs.take(2))
            val routerId = ports.head.getRouterId.asJava
            val obs = createMapperAndObserver(routerId)
            obs.awaitOnNext(1, timeout)
            obs.getOnNextEvents.get(0) shouldBe addrs.take(2).toSet

            // Add an additional port.
            ports += createRouterPort(routerId = Some(routerId),
                                      portAddress = addrs(2))
            vt.store.create(ports(2))
            obs.awaitOnNext(2, timeout)
            obs.getOnNextEvents.get(1) shouldBe addrs.toSet

            // Delete a port.
            vt.store.delete(classOf[Port], ports.head.getId)
            obs.awaitOnNext(3, timeout)
            obs.getOnNextEvents.get(2) shouldBe addrs.tail.toSet

            // Add the port back.
            vt.store.create(createRouterPort(routerId = Some(routerId),
                                             portAddress = addrs.head))
            obs.awaitOnNext(4, timeout)
            obs.getOnNextEvents.get(3) shouldBe addrs.toSet
        }

        scenario("Completes on router deletion") {
            val addrs = Seq("10.0.0.1", "10.0.0.2", "10.0.0.3")
                .map(IPv4Addr.fromString)
            val ports = createRouterWithPorts(addrs)
            val routerId = ports.head.getRouterId.asJava
            val obs = createMapperAndObserver(routerId)
            obs.awaitOnNext(1, timeout)

            vt.store.delete(classOf[Router], routerId)
            obs.awaitCompletion(timeout)
        }
    }


    private def createRouterWithPorts(addrs: Seq[IPv4Addr]): Seq[Port] = {
        val r = createRouter()
        vt.store.create(r)
        for (addr <- addrs) yield {
            val p = createRouterPort(routerId = Some(r.getId.asJava),
                                     portAddress = addr)
            vt.store.create(p)
            p
        }
    }

    private def createMapperAndObserver(routerId: UUID)
    : TestAwaitableObserver[Set[IPv4Addr]] = {
        val mapper = new RouterIPMapper(routerId, vt)
        val obs = new TestAwaitableObserver[Set[IPv4Addr]]
        mapper.ipObservable.subscribe(obs)
        obs
    }
}
