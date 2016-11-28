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
import java.io.{File, PrintWriter}
import java.nio.file.Path

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

    def verifyConfFile(poolConfig: PoolConfig, confPath: Path): Unit = {
        eventually {
            val contents = s"cat ${confLocation(confPath.toString)}".!!
            for (member <- poolConfig.members) {
                contents should include (member.address)
            }
            contents should include (poolConfig.vip.id.toString)
        }
    }

    def verifyStatus(haproxy: HaproxyHelper, poolConfig: PoolConfig): Unit = {
        eventually {
            val (upNodes, downNodes) = haproxy.getStatus
            val memberIds = poolConfig.members map {m => m.id.toString}
            val nodes = (upNodes++downNodes) map (_.toString)
            nodes should contain theSameElementsAs memberIds
        }
    }

    val vipConfig = new VipConfig(true, UUID.randomUUID(), "10.0.0.10", 80,
                                  VipSessionPersistence.SOURCE_IP)
    val healthMonitor = new HealthMonitorConfig(true, 1, 1, 1)
    val member1 = new PoolMemberConfig(true, UUID.randomUUID(), 100,
                                       "10.0.0.1", 80)
    val member2 = new PoolMemberConfig(true, UUID.randomUUID(), 100,
                                       "10.0.0.2", 80)
    val member3 = new PoolMemberConfig(true, UUID.randomUUID(), 100,
                                       "10.0.0.3", 80)
    val pool = new PoolConfig(UUID.randomUUID(), UUID.randomUUID(),
                              Set(vipConfig), Set(member1, member2),
                              healthMonitor, true, "", "")
    val poolUpdated = new PoolConfig(pool.id, pool.loadBalancerId,
                                     Set(vipConfig),
                                     Set(member1, member2, member3),
                                     healthMonitor, true, "", "")

    val haproxyScript = "../midolman/src/lib/midolman/service_containers/haproxy/haproxy-helper"

    val name = "TEST"
    val ifaceName = "iface"
    val mac = "aa:bb:cc:aa:bb:cc"
    val ip = "10.0.0.10"
    val routerIp = "10.0.0.9"
    val haproxyHelper = new HaproxyHelper(haproxyScript)
    val defaultConf =
        s"""global
            |    daemon
            |    user nobody
            |    group daemon
            |    log /dev/log    local0
            |defaults
            |    log     global
            |    timeout connect 5000
            |    timeout client 5000
            |    timeout server 5000
            |frontend A
            |    option tcplog
            |    bind *:8080
            |    default_backend B
            |backend B
            |    server X 10.0.0.1:5000
            |    server Y 10.0.0.2:5000
               """.stripMargin

    feature("creates namespace") {
        scenario("namespace is created") {
            try {
                haproxyHelper.makens(name, ifaceName, mac, ip, routerIp)
                verifyIpNetns(name, ifaceName)
            } finally {
                haproxyHelper.cleanns(name, ifaceName)
                verifyNoIpNetns(name, ifaceName)
            }
        }

        scenario("haproxy starts") {
            val tmpConf = File.createTempFile("haproxy", ".conf")
            val writer = new PrintWriter(tmpConf.getAbsolutePath, "UTF-8")
            writer.println(defaultConf)
            writer.close()
            try {
                haproxyHelper.makens(name, ifaceName, mac, ip, routerIp)
                verifyIpNetns(name, ifaceName)

                haproxyHelper.restartHaproxy(name, tmpConf.getAbsolutePath)
                verifyHaproxyRunning(name)
            } finally {
                haproxyHelper.cleanns(name, ifaceName)
                verifyNoIpNetns(name, ifaceName)
            }
        }

        scenario("cleaning non-existent namespace is a no-op") {
            verifyNoIpNetns(name, ifaceName)
            haproxyHelper.cleanns(name, ifaceName)
            verifyNoIpNetns(name, ifaceName)
        }
    }

    feature("deploys haproxy") {
        scenario("haproxy is started and restarted in a namespace") {
            val nsName = namespaceName(pool.id.toString)
            val haproxy = new HaproxyHelper(haproxyScript)
            try {
                haproxy.deploy(pool, ifaceName, "50:46:5d:a3:6d:f4",
                               "10.0.0.1", "10.0.0.2")
                verifyIpNetns(nsName, ifaceName)
                verifyHaproxyRunning(nsName)
                verifyConfFile(pool, haproxy.confPath)

                haproxy.restart(poolUpdated)
                verifyIpNetns(nsName, ifaceName)
                verifyHaproxyRunning(nsName)
                verifyConfFile(poolUpdated, haproxy.confPath)
            } finally {
                haproxy.cleanns(nsName, ifaceName)
                verifyNoIpNetns(nsName, ifaceName)
            }
        }

        scenario("status is returned") {
            val nsName = namespaceName(pool.id.toString)
            val haproxy = new HaproxyHelper(haproxyScript)
            try {
                haproxy.deploy(pool, ifaceName, "50:46:5d:a3:6d:f4",
                               "20.0.0.1", "20.0.0.2")
                verifyIpNetns(nsName, ifaceName)
                verifyHaproxyRunning(nsName)
                verifyConfFile(pool, haproxy.confPath)
                verifyStatus(haproxy, pool)

                haproxy.restart(poolUpdated)
                verifyIpNetns(nsName, ifaceName)
                verifyHaproxyRunning(nsName)
                verifyConfFile(poolUpdated, haproxy.confPath)
                verifyStatus(haproxy, poolUpdated)
            } finally {
                haproxy.cleanns(nsName, ifaceName)
                verifyNoIpNetns(nsName, ifaceName)
            }
        }
    }
}
