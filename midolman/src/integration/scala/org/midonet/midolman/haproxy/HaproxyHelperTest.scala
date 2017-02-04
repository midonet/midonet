/*
 * Copyright 2016 Midokura SARL
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

package org.midonet.midolman.haproxy

import java.util.UUID

import scala.sys.process._

import org.junit.runner.RunWith
import org.scalatest.{FeatureSpec, Matchers}
import org.scalatest.concurrent.Eventually
import org.scalatest.junit.JUnitRunner
import org.slf4j.LoggerFactory

import org.midonet.midolman.l4lb._

@RunWith(classOf[JUnitRunner])
class HaproxyHelperTest extends FeatureSpec
                        with Eventually
                        with Matchers {

    import HaproxyHelper._

    val log = LoggerFactory.getLogger(classOf[HaproxyHelperTest])

    def verifyIpNetns(name: String, iface: String): Unit = {
        eventually {
            val ns = "ip netns".!!
            ns should include (name)
            val links = s"ip netns exec $name ip link".!!
            links should include (iface)
        }
    }

    def verifyNoIpNetns(name: String, iface: String): Unit = {
        eventually {
            val ns = "ip netns".!!
            ns should not include name
            val links = s"ip link".!!
            links should not include iface
        }
    }

    def verifyHaproxyRunning(name: String): Unit = {
        eventually {
            val pid = s"ip netns pids $name".!!
            val ps = s"ps $pid".!!
            ps should include ("haproxy")
        }
    }

    def verifyConfFile(lbConfig: LoadBalancerV2Config,
                       confFile: String): Unit = {
        eventually {
            val contents = s"cat $confFile".!!
            for (pool <- lbConfig.pools) {
                for (member <- pool.members) {
                    contents should include (member.address)
                }
            }

            for (vip <- lbConfig.vips) {
                contents should include (vip.id.toString)
            }
        }
    }

    def verifyStatus(haproxy: HaproxyHelper,
                     lbConfig: LoadBalancerV2Config): Unit = {
        eventually {
            val (upNodes, downNodes) = haproxy.getStatus()
            val memberIds = lbConfig.pools.flatMap(p => p.members).map(_.id)
            (upNodes ++ downNodes) should contain theSameElementsAs memberIds
        }
    }

    val healthMonitor = HealthMonitorV2Config(UUID.randomUUID(), true, 1, 1, 1)

    val member1 = MemberV2Config(UUID.randomUUID(), true, "10.0.0.1", 80)
    val member2 = MemberV2Config(UUID.randomUUID(), true, "10.0.0.2", 80)
    val member3 = MemberV2Config(UUID.randomUUID(), true, "10.0.0.3", 80)
    val member4 = MemberV2Config(UUID.randomUUID(), true, "10.0.0.4", 80)
    val member5 = MemberV2Config(UUID.randomUUID(), true, "10.0.0.5", 80)

    val pool1 = PoolV2Config(UUID.randomUUID(), Set(member1, member2),
                             healthMonitor)
    val pool2 = PoolV2Config(UUID.randomUUID(), Set(member4, member5),
                             healthMonitor)

    val pool1Updated = PoolV2Config(pool1.id, Set(member1, member2, member3),
                                    healthMonitor)

    val listener1 = ListenerV2Config(UUID.randomUUID(), true, 80, pool1.id)
    val listener2 = ListenerV2Config(UUID.randomUUID(), true, 80, pool2.id)

    val lb1 = LoadBalancerV2Config(UUID.randomUUID(), Set(listener1),
                                   Set(pool1), true)
    val lb2 = LoadBalancerV2Config(UUID.randomUUID(),
                                   Set(listener1, listener2),
                                   Set(pool1, pool2), true)

    val lb1Updated = LoadBalancerV2Config(lb1.id, Set(listener1),
                                          Set(pool1Updated), true)

    val lbMultiPool = LoadBalancerV2Config(lb1.id,
                                           Set(listener1, listener2),
                                           Set(pool1, pool2), true)

    val haproxyScript = "../midolman/src/lib/midolman/service_containers/haproxy/haproxy-helper"


    feature("deploys haproxy") {
        scenario("haproxy is started and restarted in a namespace") {
            val ifaceName = "iface"
            val nsName = namespaceName(lb1.id.toString)
            val haproxy = new HaproxyHelper(haproxyScript)
            try {
                haproxy.deploy(lb1, ifaceName, "20.0.0.1", "20.0.0.2")
                verifyIpNetns(nsName, ifaceName)
                verifyHaproxyRunning(nsName)
                verifyConfFile(lb1, haproxy.confLoc)
                verifyStatus(haproxy, lb1)

                haproxy.restart(lb1Updated)
                verifyIpNetns(nsName, ifaceName)
                verifyHaproxyRunning(nsName)
                verifyConfFile(lb1Updated, haproxy.confLoc)
                verifyStatus(haproxy, lb1Updated)
            } finally {
                haproxy.undeploy(nsName, ifaceName)
                verifyNoIpNetns(nsName, ifaceName)
            }
        }

        scenario("two separate deployments do not interfere with each other") {
            val name1 = namespaceName(lb1.id.toString)
            val ifaceName1 = "iface1"
            val name2 = namespaceName(lb2.id.toString)
            val ifaceName2 = "iface2"
            val haproxy1 = new HaproxyHelper(haproxyScript)
            val haproxy2 = new HaproxyHelper(haproxyScript)
            try {
                haproxy1.deploy(lb1, ifaceName1, "20.0.0.1", "20.0.0.2")
                haproxy2.deploy(lb2, ifaceName2, "20.0.0.1", "20.0.0.2")
                verifyIpNetns(name1, ifaceName1)
                verifyIpNetns(name2, ifaceName2)
                verifyHaproxyRunning(name1)
                verifyHaproxyRunning(name2)
                verifyConfFile(lb1, haproxy1.confLoc)
                verifyConfFile(lb2, haproxy2.confLoc)
                verifyStatus(haproxy1, lb1)
                verifyStatus(haproxy2, lb2)

                haproxy1.restart(lb1Updated)

                verifyIpNetns(name1, ifaceName1)
                verifyIpNetns(name2, ifaceName2)
                verifyHaproxyRunning(name1)
                verifyHaproxyRunning(name2)
                verifyConfFile(lb1Updated, haproxy1.confLoc)
                verifyConfFile(lb2, haproxy2.confLoc)
                verifyStatus(haproxy1, lb1Updated)
                verifyStatus(haproxy2, lb2)
            } finally {
                haproxy1.undeploy(name1, ifaceName1)
                verifyNoIpNetns(name1, ifaceName1)

                haproxy2.undeploy(name2, ifaceName2)
                verifyNoIpNetns(name2, ifaceName2)
            }
        }

        scenario("multiple pools per load balancer") {
            val name = namespaceName(lb1.id.toString)
            val ifaceName = "iface1"
            val haproxy = new HaproxyHelper(haproxyScript)
            try {
                haproxy.deploy(lbMultiPool, ifaceName, "20.0.0.1", "20.0.0.2")
                verifyIpNetns(name, ifaceName)
                verifyHaproxyRunning(name)
                verifyConfFile(lbMultiPool, haproxy.confLoc)
                verifyStatus(haproxy, lbMultiPool)
            } finally {
                haproxy.undeploy(name, ifaceName)
                verifyNoIpNetns(name, ifaceName)
            }
        }

        scenario("starting a namespace cleans up old one") {
            val name = namespaceName(lb1.id.toString)
            val ifaceName = "iface1"
            val haproxy = new HaproxyHelper(haproxyScript)
            try {
                haproxy.deploy(lbMultiPool, ifaceName, "20.0.0.1", "20.0.0.2")

                // deploy over old namespace
                haproxy.deploy(lbMultiPool, ifaceName, "20.0.0.1", "20.0.0.2")

                verifyIpNetns(name, ifaceName)
                verifyHaproxyRunning(name)
                verifyConfFile(lbMultiPool, haproxy.confLoc)
                verifyStatus(haproxy, lbMultiPool)
            } finally {
                haproxy.undeploy(name, ifaceName)
                verifyNoIpNetns(name, ifaceName)
            }
        }
    }
}
