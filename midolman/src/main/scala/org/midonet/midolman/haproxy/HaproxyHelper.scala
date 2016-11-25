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

import java.nio.file.Files

import org.midonet.containers.ContainerCommons
import org.midonet.midolman.l4lb.PoolConfig

object HaproxyHelper {
    def namespaceName(id: String) = s"hm-${id.substring(0, 8)}"
    def confLocation(path: String) = s"$path/conf"
    def sockLocation(path: String) = s"$path/sock"
}

class HaproxyHelper(haproxyScript: String) extends ContainerCommons {

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

    private def ensureConfDir(poolConfig: PoolConfig): Unit = {
        if (confLoc == null) {
            val haproxyPath = Files.createTempDirectory(poolConfig.id.toString)
            confLoc = confLocation(haproxyPath.toString)
            sockLoc = sockLocation(haproxyPath.toString)
        }
    }

    def writeConfFile(poolConfig: PoolConfig): Unit = {
        ensureConfDir(poolConfig)
        val contents = poolConfig.generateConfigFile(Some(sockLoc))
        writeFile(contents, confLoc)
    }

    def deploy(poolConfig: PoolConfig, ifaceName: String, mac: String,
               ip: String, routerIp: String): Unit = {
        val nsName = namespaceName(poolConfig.id.toString)
        val makensCmd = makensStr(nsName, ifaceName, mac, ip, routerIp)
        val cleannsCmd = cleannsStr(nsName, ifaceName)
        executeCommands(Seq((makensCmd, cleannsCmd)))

        // restartHaproxy will just start haproxy if its not already running.
        restart(poolConfig)
    }

    def restart(poolConfig: PoolConfig): Unit = {
        if (!poolConfig.isConfigurable) {
            return
        }
        writeConfFile(poolConfig)
        execute(restartHaproxyStr(namespaceName(poolConfig.id.toString)))
    }

    def undeploy(name: String, iface: String): Unit = {
        execute(cleannsStr(name, iface))
    }
}
