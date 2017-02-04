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

package org.midonet.midolman.io

import scala.concurrent.duration._

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfter, FeatureSpec, Matchers, OneInstancePerTest}

import org.midonet.midolman.config.MidolmanConfig
import org.midonet.odp.ports.{GreTunnelPort, NetDevPort, VxLanTunnelPort}
import org.midonet.util._

@RunWith(classOf[JUnitRunner])
class TokenBucketPolicyTest extends FeatureSpec
                            with BeforeAndAfter
                            with Matchers
                            with OneInstancePerTest {
    var policy: TokenBucketPolicy = _
    val multiplier = 2

    before {
        val confStr =
            """
              |agent.datapath.global_incoming_burst_capacity = 1
              |agent.datapath.vm_incoming_burst_capacity = 1
              |agent.datapath.tunnel_incoming_burst_capacity = 8
              |agent.datapath.vtep_incoming_burst_capacity = 1
            """.stripMargin

        policy = new TokenBucketPolicy(
            MidolmanConfig.forTests(confStr),
            new TokenBucketTestRate, multiplier,
            new Bucket(_, 1, null, 0, false))
    }

    feature("Buckets are correctly linked") {
        scenario("Tunnel ports result in a leaf bucket under the root") {
            val tbgre = policy link (new GreTunnelPort("gre"), OverlayTunnel)
            val tbvxlan = policy link (new VxLanTunnelPort("vxlan"), OverlayTunnel)
            val tbvtep = policy link (new VxLanTunnelPort("vtep"), VtepTunnel)

            tbgre.underlyingTokenBucket.getCapacity should be (4)
            tbgre.underlyingTokenBucket.getName should be ("midolman-root/gre")
            tbvxlan.underlyingTokenBucket.getCapacity should be (4)
            tbvxlan.underlyingTokenBucket.getName should be ("midolman-root/vxlan")
            tbvtep.underlyingTokenBucket.getCapacity should be (1)
            tbvtep.underlyingTokenBucket.getName should be ("midolman-root/vtep")
        }

        scenario("VM ports result in a leaf bucket under the VMs bucket") {
            val tb = policy link (new NetDevPort("vm"), VirtualMachine)
            tb.underlyingTokenBucket.getCapacity should be (1)
            tb.underlyingTokenBucket.getName should be ("midolman-root/vms/vm")
        }

        scenario("The policy ensures the capacity of the root is greater or " +
                 "equal to the capacity of all leaf buckets") {
            val tb1 = policy link (new NetDevPort("vm1"), VirtualMachine)
            val tb2 = policy link (new NetDevPort("vm2"), VirtualMachine)

            tb1.underlyingTokenBucket.tryGet(1) should be (1)
            tb2.underlyingTokenBucket.tryGet(1) should be (1)
        }
    }

    feature("Buckets are correctly unlinked") {
        scenario("Tunnel ports are correctly unlinked") {
            val grePort = new GreTunnelPort("gre")
            val tbgre = policy link (grePort, OverlayTunnel)
            val vxlanPort = new VxLanTunnelPort("vxlan")
            val tbvxlan = policy link (vxlanPort, OverlayTunnel)

            policy unlink grePort
            policy unlink vxlanPort

            tbgre.underlyingTokenBucket.getNumTokens should be (TokenBucket.UNLINKED)
            tbvxlan.underlyingTokenBucket.getNumTokens should be (TokenBucket.UNLINKED)
        }

        scenario("VM ports are correctly unlinked") {
            val port = new NetDevPort("port")
            val tb = policy link (port, VirtualMachine)

            policy unlink port

            tb.underlyingTokenBucket.getNumTokens should be (TokenBucket.UNLINKED)
        }
    }

    feature("Full system simulation") {
        scenario("Token bucket simulation") {
            val confStr =
                """
                  |agent.datapath.global_incoming_burst_capacity = 256
                  |agent.datapath.vm_incoming_burst_capacity = 8
                  |agent.datapath.tunnel_incoming_burst_capacity = 16
                  |agent.datapath.vtep_incoming_burst_capacity = 1
                """.stripMargin

            val multiplier = 8
            val counter = new StatisticalCounter(3)
            val rate = new TokenBucketSystemRate(counter, multiplier)
            val policy = new TokenBucketPolicy(
                MidolmanConfig.forTests(confStr),
                rate, multiplier, new Bucket(_, multiplier, counter, 2, false))

            val tb1 = policy link (new NetDevPort("vm1"), VirtualMachine)
            val tb2 = policy link (new NetDevPort("vm2"), VirtualMachine)

            @volatile var running = true
            val addingThreads = (0 to 1) map { i =>
                new Thread() {
                    override def run(): Unit =
                        while (running) {
                            counter.addAndGet(i, 1)
                            Thread.sleep(1)
                        }
                }
            }

            var totalTokens = 0L
            val end = System.nanoTime() + (1 second).toNanos
            addingThreads foreach (_.start())
            tb1.prepare()
            do {
                if (tb1.consumeToken()) {
                    totalTokens += 1
                }
            } while (System.nanoTime() <= end)
            tb1.done()

            running = false
            addingThreads foreach (_.join())

            totalTokens should be <= (counter.getValue + 256)
        }
    }
}
