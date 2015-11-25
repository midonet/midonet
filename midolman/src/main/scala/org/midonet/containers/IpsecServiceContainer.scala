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

import java.io.{File, FileNotFoundException, PrintWriter, UnsupportedEncodingException}

import scala.sys.process._

import org.midonet.cluster.models.Commons
import org.midonet.cluster.models.Neutron.IPSecPolicy.{EncapsulationMode, TransformProtocol}
import org.midonet.cluster.models.Neutron.IPSecSiteConnection.{DpdAction, Initiator}
import org.midonet.cluster.models.Neutron.{IPSecPolicy, IPSecSiteConnection, IkePolicy}
import org.midonet.midolman.logging.MidolmanLogging
import org.midonet.packets.IPv4Addr


case class IpsecServiceDef(name: String, filepath: String, leftIp: IPv4Addr,
                           gatewayIp: IPv4Addr, mac: String)

case class IpsecConnection(ipsecPolicy: IPSecPolicy,
                           ikePolicy: IkePolicy,
                           ipsecSiteConnection: IPSecSiteConnection)

/*
 * Represents a complete configuration of a VPN service, including all
 * of the individual connections.
 *
 * This class contains the functions necessary to generate the config
 * files and vpn-helper script commands.
 */
case class IpsecServiceConfig(script: String,
                              ipsecService: IpsecServiceDef,
                              conns: List[IpsecConnection]) {

    def getSecretsFileContents = {
        val contents = new StringBuilder
        conns foreach (c => contents append
            s"""${ipsecService.leftIp} ${c.ipsecSiteConnection.getPeerAddress} : PSK \"${c.ipsecSiteConnection.getPsk}\"
               |""".stripMargin)
        contents.toString()
    }

    def subnetsString(subnets: java.util.List[Commons.IPSubnet]): String = {
        if (subnets.isEmpty) return ""
        def subnetStr(sub: Commons.IPSubnet) =
            s"${sub.getAddress}/${sub.getPrefixLength}"
        val ss = new StringBuilder(subnetStr(subnets.get(0)))
        Range(1, subnets.size()) foreach (i => s",${subnetStr(subnets.get(i))}")
        ss.toString()
    }

    def initiatorToAuto(initiator: Initiator): String = {
        initiator match {
            case Initiator.BI_DIRECTIONAL => "start"
            case Initiator.RESPONSE_ONLY => "add"
        }
    }

    def ikeVersionToikeV2(version: IkePolicy.IkeVersion): String = {
        version match {
            case IkePolicy.IkeVersion.V1 => "never"
            case IkePolicy.IkeVersion.V2 => "insist"
        }
    }

    def encapModeToIpsec(encapsulationMode: EncapsulationMode): String = {
        encapsulationMode match {
            case EncapsulationMode.TRANSPORT => "transport"
            case EncapsulationMode.TUNNEL => "tunnel"
        }
    }

    def dpdActiontoIpsec(dpdAction: DpdAction): String = {
        dpdAction match {
            case DpdAction.CLEAR => "clear"
            case DpdAction.HOLD => "hold"
            case DpdAction.DISABLED => "disabled"
            case DpdAction.RESTART_BY_PEER => "restart-by-peer"
            case DpdAction.RESTART => "restart"
        }
    }

    def transformProtocolToIpsec(transformProtocol: TransformProtocol): String = {
        transformProtocol match {
            case TransformProtocol.ESP => "esp"
            case TransformProtocol.AH => "ah"
            case TransformProtocol.AH_ESP => "ah-esp"
        }
    }

    def getConfigFileContents = {
        val contents = new StringBuilder
        contents append
            s"""config setup
               |    nat_traversal=yes
               |conn %default
               |    ikelifetime=480m
               |    keylife=60m
               |    keyingtries=%forever
               |""".stripMargin
        conns foreach (c => contents append
            s"""conn ${c.ipsecSiteConnection.getName}
               |    leftnexthop=%defaultroute
               |    rightnexthop=%defaultroute
               |    left=${ipsecService.leftIp}
               |    leftid=${ipsecService.leftIp}
               |    auto=${initiatorToAuto(c.ipsecSiteConnection.getInitiator)}
               |    leftsubnets={ ${subnetsString(c.ipsecSiteConnection.getLocalCidrsList)} }
               |    leftupdown="ipsec _updown --route yes"
               |    right=${c.ipsecSiteConnection.getPeerAddress}
               |    rightid=${c.ipsecSiteConnection.getPeerAddress}
               |    rightsubnets={ ${subnetsString(c.ipsecSiteConnection.getPeerCidrsList)} }
               |    mtu=${c.ipsecSiteConnection.getMtu}
               |    dpdaction=${dpdActiontoIpsec(c.ipsecSiteConnection.getDpdAction)}
               |    dpddelay=${c.ipsecSiteConnection.getDpdInterval}
               |    dpdtimeout=${c.ipsecSiteConnection.getDpdTimeout}
               |    authby=secret
               |    ikev2=${ikeVersionToikeV2(c.ikePolicy.getIkeVersion)}
               |    ike=aes128-sha1;modp1536
               |    ikelifetime=${c.ikePolicy.getLifetimeValue}s
               |    auth=${transformProtocolToIpsec(c.ipsecPolicy.getTransformProtocol)}
               |    phase2alg=aes128-sha1;modp1536
               |    type=${encapModeToIpsec(c.ipsecPolicy.getEncapsulationMode)}
               |    lifetime=${c.ipsecPolicy.getLifetimeValue}s
               |""".stripMargin)
        contents.toString()
    }

    val makeNsCmd =
        s"$script makens -n ${ipsecService.name} -g ${ipsecService.gatewayIp} " +
        s"-l ${ipsecService.leftIp} -i ${ipsecService.leftIp} " +
        s"-m ${ipsecService.mac}"

    val startServiceCmd =
        s"$script start_service -n ${ipsecService.name} -p ${ipsecService.filepath}"

    def initConnsCmd = {
        val cmd = new StringBuilder(s"$script init_conns -n ${ipsecService.name} " +
                                    s"-p ${ipsecService.filepath}")
        conns foreach (c => cmd append s" -c ${c.ipsecSiteConnection.getName}")
        cmd.toString
    }

    val stopServiceCmd = s"$script stop_service -n ${ipsecService.name} " +
                         s"-p ${ipsecService.filepath}"

    val cleanNsCmd = s"$script cleanns -n ${ipsecService.name}"

    val confDir = s"${ipsecService.filepath}/${ipsecService.name}/etc/"

    val confLoc = s"${ipsecService.filepath}/${ipsecService.name}/etc/ipsec.conf"

    val secretsLoc = s"${ipsecService.filepath}/${ipsecService.name}/etc/ipsec.secrets"
}

/*
 * Provides functions for dealing with service containers. Things like writing
 * to files or executing shell commands.
 */
trait ServiceContainerFunctions extends MidolmanLogging {

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
        val cmdLogger = ProcessLogger(line => log.info(line),
                                      line => log.error(line))
        cmd ! cmdLogger
    }
}

/*
 * This is a trait instead of an object for testability. It is extended by the
 * IpsecServiceContainer object, which acts as an interface to the vpn-helper
 * script.
 */
trait IpsecServiceContainerFunctions extends ServiceContainerFunctions {

    /*
     * starts a VPN service container.
     */
    def start(conf: IpsecServiceConfig): Boolean = {
        var result = false

        try {
            log.info(s"Starting VPN service ${conf.ipsecService.name}")

            new File(conf.confDir).mkdirs()
            log.info(s"Writing configuration to ${conf.confLoc}")
            writeFile(conf.getConfigFileContents, conf.confLoc)
            log.info(s"Writing secrets to ${conf.secretsLoc}")
            writeFile(conf.getSecretsFileContents, conf.secretsLoc)

            execCmd(conf.makeNsCmd)
            execCmd(conf.startServiceCmd)
            execCmd(conf.initConnsCmd)
            result = true
        } catch {
            case fnfe: FileNotFoundException =>
                log.error("Command failed", fnfe)
                result = false
            case uee: UnsupportedEncodingException =>
                log.error("Command failed", uee)
                result = false
            case re: RuntimeException =>
                log.error("Command failed", re)
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
                log.error("Command failed", re)
                result = false
        }
        result
    }
}

object IpsecServiceContainter extends IpsecServiceContainerFunctions
