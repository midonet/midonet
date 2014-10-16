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

import java.util.concurrent.TimeUnit

import scala.concurrent.Await
import scala.concurrent.duration._
import akka.util.Timeout
import akka.pattern.ask
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.midolman.DeduplicationActor.DiscardPacket
import org.midonet.midolman.FlowController.InvalidateFlowsByTag
import org.midonet.midolman.rules.{Condition, RuleResult}
import org.midonet.midolman.topology.VirtualTopologyActor.ChainRequest
import org.midonet.midolman.util.MidolmanTestCase
import org.midonet.midolman.util.RouterHelper
import org.midonet.packets.ICMP
import org.midonet.packets.util.PacketBuilder._

@Category(Array(classOf[SimulationTests]))
@RunWith(classOf[JUnitRunner])
class RouterWithFiltersTestCase extends MidolmanTestCase
                                with VMsBehindRouterFixture
                                with RouterHelper {

    private[this] implicit val timeout = new Timeout(3, TimeUnit.SECONDS)

    def testPingWithIsForwardMatchRule() {
        val chain = newOutboundChainOnRouter("egress chain", router)
        val forwardCond = new Condition()
        forwardCond.matchForwardFlow = true
        newLiteralRuleOnChain(chain, 1, forwardCond, RuleResult.Action.ACCEPT)
        clusterDataClient().routersUpdate(router)

        val resp = vtaProbe().testActor ? ChainRequest(chain.getId)
        Await.result(resp, 3 second)

        feedArpCache(vmPortNames(1),
            vmIps(1).addr,
            vmMacs(1),
            routerIp.getAddress.addr,
            routerMac)
        fishForRequestOfType[DiscardPacket](discardPacketProbe)
        fishForRequestOfType[InvalidateFlowsByTag](flowProbe())
        drainProbes()

        val pckt = { eth src vmMacs(1) dst routerMac } <<
                   { ip4 src vmIps(1).addr dst routerIp.getAddress.addr } <<
                   icmp.echo.request

        triggerPacketIn(vmPortNames(1), pckt)
        expectPacketOnPort(vmPorts(1).getId)
        requestOfType[DiscardPacket](discardPacketProbe)
        expectEmitIcmp(routerMac, routerIp.getAddress, vmMacs(1), vmIps(1),
                       ICMP.TYPE_ECHO_REPLY, ICMP.CODE_NONE)
    }
}
