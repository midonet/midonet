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
import org.midonet.midolman.l4lb.{HealthMonitorConfig, PoolConfig, PoolMemberConfig, VipConfig}
import org.midonet.midolman.state.l4lb.VipSessionPersistence
import org.scalatest.concurrent.Eventually
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FeatureSpec, ShouldMatchers}
import org.slf4j.LoggerFactory


@RunWith(classOf[JUnitRunner])
class HaproxyHelperTest extends FeatureSpec
                        with Eventually
                        with ShouldMatchers {

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

    def verifyConfFile(poolConfig: PoolConfig, confFile: String): Unit = {
        eventually {
            val contents = s"cat $confFile".!!
            for (member <- poolConfig.members) {
                contents should include (member.address)
            }
            contents should include (poolConfig.vip.id.toString)
        }
    }

    val vipConfig = new VipConfig(true, UUID.randomUUID(), "10.0.0.10", 80,
                                  VipSessionPersistence.SOURCE_IP)
    val healthMonitor = new HealthMonitorConfig(true, 10, 20, 30)
    val member1 = new PoolMemberConfig(true, UUID.randomUUID(), 100,
                                       "10.0.0.1", 80)
    val member2 = new PoolMemberConfig(true, UUID.randomUUID(), 100,
                                       "10.0.0.2", 80)
    val member3 = new PoolMemberConfig(true, UUID.randomUUID(), 100,
                                       "10.0.0.3", 80)
    val pool1 = new PoolConfig(UUID.randomUUID(), UUID.randomUUID(),
                              Set(vipConfig), Set(member1, member2),
                              healthMonitor, true, "", "")
    val pool2 = new PoolConfig(UUID.randomUUID(), UUID.randomUUID(),
                               Set(vipConfig), Set(member1, member2),
                               healthMonitor, true, "", "")
    val pool1Updated = new PoolConfig(pool1.id, pool1.loadBalancerId,
                                     Set(vipConfig),
                                     Set(member1, member2, member3),
                                     healthMonitor, true, "", "")

    val haproxyScript = "../midolman/src/lib/midolman/service_containers/haproxy/haproxy-helper"


    feature("deploys haproxy") {
        scenario("haproxy is started and restarted in a namespace") {
            val ifaceName = "iface"
            val nsName = namespaceName(pool1.id.toString)
            val haproxy = new HaproxyHelper(haproxyScript)
            try {
                haproxy.deploy(pool1, ifaceName, "50:46:5d:a3:6d:f4",
                               "20.0.0.1", "20.0.0.2")
                verifyIpNetns(nsName, ifaceName)
                verifyHaproxyRunning(nsName)
                verifyConfFile(pool1, haproxy.confLoc)

                haproxy.restart(pool1Updated)
                verifyIpNetns(nsName, ifaceName)
                verifyHaproxyRunning(nsName)
                verifyConfFile(pool1Updated, haproxy.confLoc)
            } finally {
                haproxy.undeploy(nsName, ifaceName)
                verifyNoIpNetns(nsName, ifaceName)
            }
        }

        scenario("two separate deployments do not interfere with each other") {
            val name1 = namespaceName(pool1.id.toString)
            val ifaceName1 = "iface1"
            val name2 = namespaceName(pool2.id.toString)
            val ifaceName2 = "iface2"
            val haproxy1 = new HaproxyHelper(haproxyScript)
            val haproxy2 = new HaproxyHelper(haproxyScript)
            try {
                haproxy1.deploy(pool1, ifaceName1, "50:46:5d:a3:6d:f4",
                                "20.0.0.1", "20.0.0.2")
                haproxy2.deploy(pool2, ifaceName2, "50:46:5d:a3:6d:f4",
                                "20.0.0.1", "20.0.0.2")
                verifyIpNetns(name1, ifaceName1)
                verifyIpNetns(name2, ifaceName2)
                verifyHaproxyRunning(name1)
                verifyHaproxyRunning(name2)
                verifyConfFile(pool1, haproxy1.confLoc)
                verifyConfFile(pool2, haproxy2.confLoc)

                haproxy1.restart(pool1Updated)

                verifyIpNetns(name1, ifaceName1)
                verifyIpNetns(name2, ifaceName2)
                verifyHaproxyRunning(name1)
                verifyHaproxyRunning(name2)
                verifyConfFile(pool1Updated, haproxy1.confLoc)
                verifyConfFile(pool2, haproxy2.confLoc)
            } finally {
                haproxy1.undeploy(name1, ifaceName1)
                verifyNoIpNetns(name1, ifaceName1)

                haproxy2.undeploy(name2, ifaceName2)
                verifyNoIpNetns(name2, ifaceName2)
            }
        }
    }
}
