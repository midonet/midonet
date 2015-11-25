package org.midonet.containers

import scala.sys.process._

import java.io.{UnsupportedEncodingException, FileNotFoundException, PrintWriter, File}
import org.slf4j.LoggerFactory


case class VPNServiceDef(name: String, filepath: String, leftIp: String,
                         leftSubLen: Int, gatewayIp: String, mac: String)

case class VPNConnectionDef(var name: String, var remotePeerIp: String, auto: String,
                            leftSubs: String, rightSubs: String, mtu: Int,
                            dpdAction: String, dpdDelay: Int, dpdTimeout: Int,
                            ikev2: String, encryptAlg: String,
                            authAlg: String, pfs: String, txProt: String,
                            ikeLifetime: Int, vpnType: String,
                            lifetime: String, secret: String) {

    // useful for testing
    def copy = new VPNConnectionDef(name, remotePeerIp, auto,
                                    leftSubs, rightSubs, mtu,
                                    dpdAction, dpdDelay, dpdTimeout,
                                    ikev2, encryptAlg,
                                    authAlg, pfs, txProt,
                                    ikeLifetime, vpnType,
                                    lifetime, secret)
}

/*
 * Represents a complete configuration of a VPN service, including all
 * of the individual connections.
 *
 * This class contains the functions necessary to generate the config
 * files and vpn-helper script commands.
 */
case class VpnServiceConfig(script: String,
                            vpnService: VPNServiceDef,
                            conns: List[VPNConnectionDef]) {

    def getSecretsFileContents = {
        var contents = new StringBuilder
        conns foreach (c => contents append
            s"""${vpnService.leftIp} ${c.remotePeerIp} : PSK \"${c.secret}\"
               |""".stripMargin)
        contents.toString()
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
               |    left=${vpnService.leftIp}
               |    leftid=${vpnService.leftIp}
               |    auto=${c.auto}
               |    leftsubnets={ ${c.leftSubs} }
               |    leftupdown="ipsec_updown --route yes"
               |    right=${c.remotePeerIp}
               |    rightid=${c.remotePeerIp}
               |    rightsubnets={ ${c.rightSubs} }
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
        contents.toString
    }

    def makeNsCmd = s"""$script makens -n ${vpnService.name} -g ${vpnService.gatewayIp} -l ${vpnService.leftIp} -i ${vpnService.leftIp} -m ${vpnService.mac}"""

    def startServiceCmd = s"""$script start_service -n ${vpnService.name} -p ${vpnService.filepath}"""

    def initConnsCmd = {
        val cmd = new StringBuilder(s"""$script init_conns -n ${vpnService.name} -p ${vpnService.filepath}""")
        conns foreach (c => cmd append s""" -c ${c.name}""")
        cmd.toString
    }

    def stopServiceCmd = s"""$script stop_service -n ${vpnService.name} -p ${vpnService.filepath}"""

    def cleanNsCmd = s"""$script cleanns -n ${vpnService.name}"""

    def confLoc = s"""${vpnService.filepath}/${vpnService.name}/etc/ipsec.conf"""

    def secretsLoc = s"""${vpnService.filepath}/${vpnService.name}/etc/ipsec.secrets"""
}

/*
 * Provides functions for dealing with service containers. Things like writing
 * to files or executing shell commands.
 */
trait ServiceContainerFunctions {

    private final val log = LoggerFactory.getLogger("midonet.containers")

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
 * VpnServiceContainer object, which acts as an interface to the vpn-helper
 * script.
 */
trait VpnServiceContainerFunctions extends ServiceContainerFunctions {

    private final val log = LoggerFactory.getLogger("org.midonet.containers.vpn")

    /*
     * starts a VPN service container.
     */
    def start(conf: VpnServiceConfig): Boolean = {
        var result = false

        try {
            log.info(s"""starting VPN service ${conf.vpnService.name}""")

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
    def stop(conf: VpnServiceConfig): Boolean = {
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

object VpnServiceContainter extends VpnServiceContainerFunctions
