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

import java.io.{File, PrintWriter}

import org.midonet.midolman.l4lb.PoolConfig
import org.midonet.midolman.logging.MidolmanLogging

import scala.sys.process._

object HaproxyHelper {
    def namespaceName(id: String) = s"hm-${id.substring(0, 8)}"
}

class HaproxyHelper(haproxyScript: String) extends MidolmanLogging {

    import HaproxyHelper.namespaceName

    var confFile: File = _

    def makens(name: String, iface: String, mac: String, ip: String,
               routerIp: String) = {
        s"$haproxyScript makens $name $iface $mac $ip $routerIp".!!
    }

    def cleanns(name: String, iface: String) = {
        s"$haproxyScript cleanns $name $iface".!!
    }

    def restartHaproxy(name: String, confLocation: String) = {
        s"$haproxyScript restart_ha $name $confLocation".!!
    }

    def writeConfFile(poolConfig: PoolConfig): Unit = {
        val confDir = File.createTempFile("haproxy", ".dir")
        /*
         * deleting the file first because we actually want a directory.
         * The createTempFile call will give us a path into the tmp
         * filesystem.
         */
        confDir.delete()
        confDir.mkdir()
        confFile = new File(s"${confDir.getAbsolutePath}/conf")
        val writer = new PrintWriter(confFile.getAbsolutePath, "UTF-8")
        writer.println(poolConfig.generateConfigFile(s"${confDir.getAbsolutePath}/sock"))
        writer.close()
    }

    def deploy(poolConfig: PoolConfig, ifaceName: String, mac: String,
               ip: String, routerIp: String): Unit = {
        if (!poolConfig.isConfigurable) {
            return
        }

        writeConfFile(poolConfig)

        val name = namespaceName(poolConfig.id.toString)

        makens(name, ifaceName, mac, ip, routerIp)

        // restartHaproxy will just start haproxy if its not already running.
        restartHaproxy(name, confFile.getAbsolutePath)
    }

    def restart(poolConfig: PoolConfig): Unit = {
        writeConfFile(poolConfig)
        restartHaproxy(namespaceName(poolConfig.id.toString),
                       confFile.getAbsolutePath)
    }
}
