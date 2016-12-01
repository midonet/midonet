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

import java.nio.channels.IllegalSelectorException
import java.nio.channels.spi.SelectorProvider
import java.nio.file.Files
import java.util.UUID

import org.midonet.containers.ContainerCommons
import org.midonet.midolman.l4lb.{HaproxyHealthMonitor, LoadBalancerV2Config}
import org.midonet.netlink.{NetlinkSelectorProvider, UnixDomainChannel}
import org.midonet.util.AfUnix

object HaproxyHelper {
    def namespaceName(id: String) = s"hm-${id.substring(0, 8)}"
    def confLocation(path: String) = s"$path/conf"
    def sockLocation(path: String) = s"$path/sock"
}

class HaproxyHelper(haproxyScript: String) extends ContainerCommons {

    import HaproxyHealthMonitor._
    import HaproxyHelper._

    var confLoc: String = _
    var sockLoc: String = _

    private def makensStr(name: String, iface: String, mac: String, ip: String,
                          routerIp: String) = {
        s"$haproxyScript makens $name $iface $mac $ip $routerIp"
    }

    private def cleannsStr(name: String, iface: String) = {
        s"$haproxyScript cleanns $name $iface"
    }

    private def restartHaproxyStr(name: String) = {
        s"$haproxyScript restart_ha $name $confLoc"
    }

    private def ensureConfDir(lbV2Config: LoadBalancerV2Config): Unit = {
        if (confLoc == null) {
            val haproxyPath = Files.createTempDirectory(lbV2Config.id.toString)
            confLoc = confLocation(haproxyPath.toString)
            sockLoc = sockLocation(haproxyPath.toString)
        }
    }

    def writeConfFile(lbV2Config: LoadBalancerV2Config): Unit = {
        ensureConfDir(lbV2Config)
        val contents = lbV2Config.generateConfigFile(sockLoc)
        writeFile(contents, confLoc)
    }

    def deploy(lbV2Config: LoadBalancerV2Config, ifaceName: String,
               mac: String, ip: String, routerIp: String): Unit = {
        val nsName = namespaceName(lbV2Config.id.toString)
        val makensCmd = makensStr(nsName, ifaceName, mac, ip, routerIp)
        val cleannsCmd = cleannsStr(nsName, ifaceName)
        executeCommands(Seq((makensCmd, cleannsCmd)))

        // restartHaproxy will just start haproxy if its not already running.
        restart(lbV2Config)
    }

    def restart(lbV2Config: LoadBalancerV2Config): Unit = {
        writeConfFile(lbV2Config)
        execute(restartHaproxyStr(namespaceName(lbV2Config.id.toString)))
    }

    def undeploy(name: String, iface: String): Unit = {
        execute(cleannsStr(name, iface))
    }

    def makeChannel(): UnixDomainChannel = SelectorProvider.provider() match {
        case nl: NetlinkSelectorProvider =>
            nl.openUnixDomainSocketChannel(AfUnix.Type.SOCK_STREAM)
        case other =>
            log.error("Invalid selector type: {} => jdk-bootstrap shadowing " +
              "may have failed ?", other.getClass)
            throw new IllegalSelectorException
    }

    /*
     * Returns a pair of sets. The left side set is the set of member
     * nodes that haproxy has detected as UP. The right side set is the
     * set of member nodes that haproxy has detected as DOWN.
     */
    def getStatus: (Set[UUID], Set[UUID]) = {
        val channel = makeChannel()
        val statusInfo = getHaproxyStatus(sockLoc, channel)
        parseResponse(statusInfo)
    }
}
