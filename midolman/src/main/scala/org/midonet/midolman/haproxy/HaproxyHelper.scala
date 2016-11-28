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

import java.nio.file.{Files, Path}
import java.io.{File, PrintWriter}
import java.nio.channels.IllegalSelectorException
import java.nio.channels.spi.SelectorProvider
import java.util.UUID

import org.midonet.midolman.l4lb.{HaproxyHealthMonitor, PoolConfig}
import org.midonet.midolman.logging.MidolmanLogging
import org.midonet.netlink.{NetlinkSelectorProvider, UnixDomainChannel}
import org.midonet.util.AfUnix

import scala.sys.process._

object HaproxyHelper {
    def namespaceName(id: String) = s"hm-${id.substring(0, 8)}"
    def confLocation(path: String) = s"$path/conf"
    def sockLocation(path: String) = s"$path/sock"
}

class HaproxyHelper(haproxyScript: String) extends MidolmanLogging {

    import HaproxyHelper._
    import HaproxyHealthMonitor._

    var confPath: Path = _

    def makens(name: String, iface: String, mac: String, ip: String,
               routerIp: String) = {
        s"$haproxyScript makens $name $iface $mac $ip $routerIp".!!
    }

    def cleanns(name: String, iface: String) = {
        s"$haproxyScript cleanns $name $iface".!!
    }

    def restartHaproxy(name: String, confLoc: String = null) = {
        val configLocation = confLoc match {
            case null => confLocation(confPath.toString)
            case _ => confLoc
        }
        s"$haproxyScript restart_ha $name $configLocation".!!
    }

    def writeConfFile(poolConfig: PoolConfig): Unit = {
        confPath = Files.createTempDirectory(poolConfig.id.toString)
        val writer = new PrintWriter(confLocation(confPath.toString), "UTF-8")
        val confContents = poolConfig.generateConfigFile(
            Some(sockLocation(confPath.toString)))
        writer.println(confContents)
        writer.close()
    }

    def deploy(poolConfig: PoolConfig, ifaceName: String, mac: String,
               ip: String, routerIp: String): Unit = {
        makens(namespaceName(poolConfig.id.toString), ifaceName, mac, ip,
                             routerIp)
        // restartHaproxy will just start haproxy if its not already running.
        restart(poolConfig)
    }

    def restart(poolConfig: PoolConfig): Unit = {
        if (!poolConfig.isConfigurable) {
            return
        }
        writeConfFile(poolConfig)
        restartHaproxy(namespaceName(poolConfig.id.toString))
    }

    def makeChannel(): UnixDomainChannel = SelectorProvider.provider() match {
        case nl: NetlinkSelectorProvider =>
            nl.openUnixDomainSocketChannel(AfUnix.Type.SOCK_STREAM)
        case other =>
            log.error("Invalid selector type: {} => jdk-bootstrap shadowing " +
              "may have failed ?", other.getClass)
            throw new IllegalSelectorException
    }

    def getStatus: (Set[UUID], Set[UUID]) = {
        val channel = makeChannel()
        val sockLoc = sockLocation(confPath.toString)
        val statusInfo = getHaproxyStatus(sockLoc, channel)
        parseResponse(statusInfo)
    }
}
