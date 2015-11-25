/*
 * Copyright 2015 Midokura SARL
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

package org.midonet.containers

import org.midonet.packets.{IPv4Addr, IPv4Subnet}

import scala.sys.process._

import java.io.{UnsupportedEncodingException, FileNotFoundException, PrintWriter, File}
import org.slf4j.{Logger, LoggerFactory}


case class IpsecServiceDef(name: String, filepath: String, leftIp: IPv4Addr,
                           gatewayIp: IPv4Addr, mac: String)

// TODO: almost all of the strings here should be converted to enums, once
// the agent models are defined.
case class IpsecConnectionDef(var name: String, var remotePeerIp: IPv4Addr,
                              auto: String,
                              leftSubs: List[IPv4Subnet],
                              rightSubs: List[IPv4Subnet], mtu: Int,
                              dpdAction: String, dpdDelay: Int, dpdTimeout: Int,
                              ikev2: String, encryptAlg: String,
                              authAlg: String, pfs: String, txProt: String,
                              ikeLifetime: Int, vpnType: String,
                              lifetime: String, secret: String)

/*
 * Represents a complete configuration of a VPN service, including all
 * of the individual connections.
 *
 * This class contains the functions necessary to generate the config
 * files and vpn-helper script commands.
 */
case class IpsecServiceConfig(script: String,
                            ipsecService: IpsecServiceDef,
                            conns: List[IpsecConnectionDef]) {

    def getSecretsFileContents = {
        var contents = new StringBuilder
        conns foreach (c => contents append
            s"""${ipsecService.leftIp} ${c.remotePeerIp} : PSK \"${c.secret}\"
               |""".stripMargin)
        contents.toString()
    }

    def subnetsString(subnets: List[IPv4Subnet]): String = {
        if (subnets.isEmpty) return ""
        val ss = new StringBuilder
        ss append subnets.head.toString

        subnets.tail foreach(s => ss append("," + s.toString))

        ss.toString()
    }

    def getConfigFileContents = {
        var contents = new StringBuilder
        contents append
            s"""config setup
               |    nat_traversal=yes
               |conn %default
               |    ikelifetime=480m
               |    keylife=60m
               |    keyingtries=%forever
               |""".stripMargin
        conns foreach (c => contents append
            s"""conn ${c.name}
               |    leftnexthop=%defaultroute
               |    rightnexthop=%defaultroute
               |    left=${ipsecService.leftIp}
               |    leftid=${ipsecService.leftIp}
               |    auto=${c.auto}
               |    leftsubnets={ ${subnetsString(c.leftSubs)} }
               |    leftupdown="ipsec _updown --route yes"
               |    right=${c.remotePeerIp}
               |    rightid=${c.remotePeerIp}
               |    rightsubnets={ ${subnetsString(c.rightSubs)} }
               |    mtu=${c.mtu}
               |    dpdaction=${c.dpdAction}
               |    dpddelay=${c.dpdDelay}
               |    dpdtimeout=${c.dpdTimeout}
               |    authby=secret
               |    ikev2=${c.ikev2}
               |    ike=aes128-sha1;modp1536
               |    ikelifetime=${c.ikeLifetime}s
               |    auth=${c.authAlg}
               |    phase2alg=aes128-sha1;modp1536
               |    type=${c.vpnType}
               |    lifetime=${c.lifetime}s
               |""".stripMargin)
        contents.toString()
    }

    val makeNsCmd = s"""$script makens -n ${ipsecService.name} -g ${ipsecService.gatewayIp} -l ${ipsecService.leftIp} -i ${ipsecService.leftIp} -m ${ipsecService.mac}"""

    val startServiceCmd = s"""$script start_service -n ${ipsecService.name} -p ${ipsecService.filepath}"""

    def initConnsCmd = {
        val cmd = new StringBuilder(s"""$script init_conns -n ${ipsecService.name} -p ${ipsecService.filepath}""")
        conns foreach (c => cmd append s""" -c ${c.name}""")
        cmd.toString
    }

    val stopServiceCmd = s"""$script stop_service -n ${ipsecService.name} -p ${ipsecService.filepath}"""

    val cleanNsCmd = s"""$script cleanns -n ${ipsecService.name}"""

    val confLoc = s"""${ipsecService.filepath}/${ipsecService.name}/etc/ipsec.conf"""

    val secretsLoc = s"""${ipsecService.filepath}/${ipsecService.name}/etc/ipsec.secrets"""
}

/*
 * Provides functions for dealing with service containers. Things like writing
 * to files or executing shell commands.
 */
trait ServiceContainerFunctions {

    val log: Logger

    def writeFile(contents: String, location: String): Boolean = {
        val file = new File(location)
        file.getParentFile.mkdirs()
        val writer = new PrintWriter(file, "UTF-8")
        var success = false
        try {
            writer.print(contents)
            success = true
        } catch {
            case fnfe: FileNotFoundException =>
                log.error("FileNotFoundException while trying to write " +
                    location)
                throw fnfe
            case uee: UnsupportedEncodingException =>
                log.error("UnsupportedEncodingException while trying to " +
                    "write " + location)
                throw uee
        } finally {
            writer.close()
        }
        success
    }

    /*
     * executes a command and logs the output.
     */
    def execCmd(cmd: String): Unit = {
        log.info(s"""CMD: $cmd""")
        val output = cmd.!!
        log.info(output)
    }
}

/*
 * This is a trait instead of an object for testability. It is extended by the
 * IpsecServiceContainer object, which acts as an interface to the vpn-helper
 * script.
 */
trait IpsecServiceContainerFunctions extends ServiceContainerFunctions {

    override val log = LoggerFactory.getLogger("org.midonet.containers.ipsec")

    /*
     * starts a VPN service container.
     */
    def start(conf: IpsecServiceConfig): Boolean = {
        var result = false

        try {
            log.info(s"""starting VPN service ${conf.ipsecService.name}""")

            log.info(s"""Writing to ${conf.confLoc}""")
            writeFile(conf.getConfigFileContents, conf.confLoc)
            log.info(s"""Writing to ${conf.secretsLoc}""")
            writeFile(conf.getSecretsFileContents, conf.secretsLoc)

            execCmd(conf.makeNsCmd)
            execCmd(conf.startServiceCmd)
            execCmd(conf.initConnsCmd)
            result = true
        } catch {
            case fnfe: FileNotFoundException =>
                result = false
            case uee: UnsupportedEncodingException =>
                result = false
            case re: RuntimeException =>
                log.error("command failed")
                result = false
        }
        result
    }

    /*
     * stops a VPN service container and cleans up the namespace.
     */
    def stop(conf: IpsecServiceConfig): Boolean = {
        var result = false
        try {
            execCmd(conf.stopServiceCmd)
            execCmd(conf.cleanNsCmd)
            result = true
        } catch {
            case re: RuntimeException =>
                log.error("command failed")
                result = false
        }
        result
    }
}

object IpsecServiceContainter extends IpsecServiceContainerFunctions
