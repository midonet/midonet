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
package org.midonet.midolman.l4lb

import java.util.UUID
import scala.collection.immutable.Set
import org.slf4j.{LoggerFactory, Logger}

object PoolConfig {
    val SOCKET = "sock"
    val CONF = "conf"
    val PID = "pid"
}
/**
 * Represents a pool object local to the host.  The host that acts as a
 * health monitor only needs to know minimal amount of pool data to run the
 * service.
 */
class PoolConfig(val id: UUID,
                 val loadBalancerId: UUID,
                 val vips: Set[VipConfig],
                 val members: Set[PoolMemberConfig],
                 val healthMonitor: HealthMonitorConfig,
                 val adminStateUp: Boolean,
                 val l4lbFileLocs: String,
                 val nsPostFix: String) {
    import PoolConfig._

    private final val log: Logger
        = LoggerFactory.getLogger(classOf[PoolConfig])

    // we just need a single vip. Any is fine as long as it is configurable
    val vip = {
        val confVips = vips filter (_.isConfigurable)
        if (confVips.isEmpty) {
            null
        } else {
            confVips.toList.min(Ordering.by((vip: VipConfig) => vip.id))
        }
    }

    val haproxyConfFileLoc = l4lbFileLocs + id.toString + "/" + CONF
    val haproxyPidFileLoc = l4lbFileLocs + id.toString + "/" + PID
    val haproxySockFileLoc = l4lbFileLocs + id.toString + "/" + SOCKET

    // make sure that the config has the necessary fields to write a
    // valid config
    def isConfigurable: Boolean =
        adminStateUp && id != null && vips != null && healthMonitor != null &&
        vip != null && loadBalancerId != null && healthMonitor.isConfigurable &&
        members.forall (_.isConfigurable)

    def generateConfigFile(sockFile: String = null): String = {
        if (!isConfigurable) {
            log.error("haproxy config not complete")
            return ""
        }
        val sockFileLocation = {
            if (sockFile == null) {
                haproxySockFileLoc
            } else {
                sockFile
            }
        }
        val conf = new StringBuilder()
        conf append
s"""global
        daemon
        user nobody
        group daemon
        log /dev/log local0
        log /dev/log local1 notice
        stats socket $sockFileLocation mode 0666 level user
defaults
        log global
        retries 3
        timeout connect 5000
        timeout client 5000
        timeout server 5000
frontend ${vip.id.toString}
        option tcplog
        bind *:${vip.port}
        mode tcp
        default_backend $id
backend $id
        timeout check ${healthMonitor.timeout}s
"""
        members.foreach(x => conf.append(s"        server ${x.id.toString} " +
            s"${x.address}:${x.port} check inter ${healthMonitor.delay}s " +
            s"fall ${healthMonitor.maxRetries}\n"))
        conf.toString()
    }

    override def equals(other: Any) = other match {
        case that: PoolConfig =>
            this.adminStateUp == that.adminStateUp &&
            this.id == that.id &&
            this.loadBalancerId == that.loadBalancerId &&
            this.l4lbFileLocs == that.l4lbFileLocs &&
            this.nsPostFix == that.nsPostFix
            this.adminStateUp == that.adminStateUp &&
            this.vip == that.vip &&
            this.vips == that.vips &&
            this.healthMonitor == that.healthMonitor &&
            this.members == that.members
        case _ => false
    }
}
