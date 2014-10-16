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

package org.midonet.sdn.flows

import java.util.UUID

import scala.collection.mutable
import scala.ref.WeakReference
import scala.util.Random

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{GivenWhenThen, BeforeAndAfter, Matchers, FeatureSpecLike}

import org.midonet.midolman.layer3.Route
import org.midonet.midolman.layer3.Route.NextHop
import org.midonet.packets.{IPv4Addr, MAC}
import org.midonet.sdn.flows.FlowTagger.FlowTag
import org.midonet.sdn.flows.FlowTagger.{tagForDevice, tagForFloodedFlowsByDstMac,
                                         tagForArpRequests, tagForVlanPort, tagForBroadcast,
                                         tagForBridgePort, tagForDpPort, tagForTunnelRoute,
                                         tagForTunnelKey, tagForRoute, tagForDestinationIp,
                                         tagForBgp}

@RunWith(classOf[JUnitRunner])
class FlowTaggerTest extends FeatureSpecLike
                     with Matchers
                     with BeforeAndAfter
                     with GivenWhenThen {

    feature("FlowTags are cached and weakly referenced") {
        val tagTypes = mutable.Set[Class[_]]()
        var size = 0
        val tagClasses = flowTags()
        for (tag <- flowTags()) {
            val klass = tagClasses(size)().getClass
            tagTypes += klass
            size += 1

            scenario(klass.getName.split("""\$""").last) {
                doFlowTagChecks(tag)
            }
        }

        scenario("All queries result in distinct FlowTags") {
            tagTypes.size should be(size)
        }
    }

    def flowTags() = {
        val rand = new Random
        val short = rand.nextInt(Short.MaxValue).toShort
        val uuid = UUID.randomUUID()
        val mac = MAC.random()
        val int = rand.nextInt()
        val ip = IPv4Addr.random
        val route = new Route(int, int, int, int, NextHop.LOCAL, uuid, int, int, "", uuid)
        List(
            () => tagForDevice(uuid),
            () => tagForFloodedFlowsByDstMac(uuid, short, mac),
            () => tagForArpRequests(uuid),
            () => tagForVlanPort(uuid, mac, short, uuid),
            () => tagForTunnelRoute(int, int),
            () => tagForBroadcast(uuid, uuid),
            () => tagForBridgePort(uuid, uuid),
            () => tagForDpPort(int),
            () => tagForTunnelKey(int),
            () => tagForRoute(route),
            () => tagForDestinationIp(uuid, ip),
            () => tagForBgp(uuid)
        )
    }

    val doFlowTagChecks = flowTagIsCached _ andThen (WeakReference(_)) andThen
                          flowTagIsWeaklyReferenced

    def flowTagIsCached(getTag: () => FlowTag): FlowTag = {
        val tag = getTag()
        tag should be theSameInstanceAs getTag()
        tag
    }

    def flowTagIsWeaklyReferenced(flowTag: WeakReference[FlowTag]): Unit = {
        var retries = 100
        while (flowTag.underlying.get() ne null) {
            System.gc()
            retries -= 1
            if (retries == 0) {
                fail("FlowTag was not GCed")
            }
        }
    }
}
