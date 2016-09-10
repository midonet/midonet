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
package org.midonet.midolman.simulation

import java.nio.{BufferOverflowException, ByteBuffer}
import java.util.UUID

import scala.collection.JavaConversions._
import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.concurrent.duration._

import org.midonet.cluster.data.dhcp.{Host, Opt121, Subnet}
import org.midonet.packets._
import org.midonet.util.logging.Logger

/**
 * DHCP option value parser based on RFC 2132
 *   https://www.ietf.org/rfc/rfc2132.txt
 */
object DhcpValueParser {
    import DHCPOption._

    val CodeToOption: Map[Byte, DHCPOption.Code] = CODE_TO_OPTION.asScala.map({
            case (k, v) => (Byte2byte(k), v)
    }).toMap

    val NameToCode: Map[String, Byte] = NAME_TO_CODE.asScala.map({
        case (k, v) => (k, Byte2byte(v))
    }).toMap

    type SimpleParser = String => Option[Array[Byte]]

    private[midolman]
    implicit class DhcpValueString(val s: String) {
        def splitWithComma: Array[String] =
            s.replaceAll("\\s", "").split(",")

        def toCanonicalNumber: String = s.trim.toLowerCase
    }

    private[midolman]
    def parseIpAddresses(dhcpValue: String): Option[Array[Byte]] = try {
        Some((for (ipString <- dhcpValue.splitWithComma)
        yield IPv4Addr.stringToBytes(ipString)).flatten)
    } catch {
        case _: IllegalArgumentException => None
    }

    private[midolman]
    def parseSingleIpAddress(dhcpValue: String): Option[Array[Byte]] = try {
        Some(IPv4Addr.stringToBytes(dhcpValue))
    } catch {
        case _: IllegalArgumentException => None
    }

    private[midolman]
    def parseNumbers(dhcpValue: String, len: Int): Option[Array[Byte]] = try {
        require(len == 2 || len == 4, "The length should be 2 or 4.")
        val numbers = dhcpValue.splitWithComma.map(_.toInt)
        val buff = ByteBuffer.allocate(len * numbers.length)
        numbers.foreach { n =>
            if (len == 2) {
                if (n > Short.MaxValue || n < Short.MinValue) {
                    None
                } else {
                    buff.putShort(n.toShort)
                }
            } else if (len == 4) {
                if (n > Int.MaxValue || n < Int.MinValue) {
                    None
                } else {
                    buff.putInt(n)
                }
            }
        }
        Some(buff.array())
    } catch {
        case _: NumberFormatException | _: BufferOverflowException => None
    }

    private[midolman]
    def parseBoolean(dhcpValue: String): Option[Array[Byte]] = try {
        Some(if (dhcpValue.trim.toBoolean) Array(1.toByte) else Array(0.toByte))
    } catch {
        case _: IllegalArgumentException =>
            dhcpValue.toCanonicalNumber match {
                case "0" | "0x0" | "0x00" => Some(Array(0.toByte))
                case "1" | "0x1" | "0x01" => Some(Array(1.toByte))
                case _                    => None
            }
    }

    private[midolman]
    def parseCidr(dhcpValue: String): Option[Array[Byte]] = try {
        Some((for {
            cidrString <- dhcpValue.splitWithComma
            cidr = IPv4Subnet.fromCidr(cidrString)
            netmask = IPv4Subnet.prefixLenToBytes(cidr.getPrefixLen)
            networkAddress = cidr.getAddress.toBytes.zip(netmask).map {
                case (a: Byte, m: Byte) => (a & m).toByte
            }
        } yield networkAddress ++ netmask).flatten)
    } catch {
        case _: IllegalArgumentException => None
    }

    /*
     * 1, 2, 4, 8 mapped to specific meanings i.e., NetBIOS over TCP/IP Node
     * Type Option in RFC 2132
     *
     * The following example represents  NetBIOS over TCP/IP Node Type Option.
     *
     * Value         Node Type
     * -----         ---------
     * 0x1           B-node
     * 0x2           P-node
     * 0x4           M-node
     * 0x8           H-node
     */
    private[midolman]
    def parse1248(dhcpValue: String): Option[Array[Byte]] =
        dhcpValue.toCanonicalNumber match {
            case "1" | "0x1" | "0x01" => Some(Array(0x01.toByte))
            case "2" | "0x2" | "0x02" => Some(Array(0x02.toByte))
            case "4" | "0x4" | "0x04" => Some(Array(0x04.toByte))
            case "8" | "0x8" | "0x08" => Some(Array(0x08.toByte))
            case _                    => None
        }

    /*
     * 1 - 3 mapped to specific meanings i.e., Option Overload in RFC 2132
     *
     * The following example represents Option Overload in RFC 2132.
     *
     * Value   Meaning
     * -----   --------
     *   1     the 'file' field is used to hold options
     *   2     the 'sname' field is used to hold options
     *   3     both fields are used to hold options
     */
    private[midolman]
    def parse1to3(dhcpValue: String): Option[Array[Byte]] =
        dhcpValue.toCanonicalNumber match {
            case "1" | "0x1" | "0x01" => Some(Array(0x01.toByte))
            case "2" | "0x2" | "0x02" => Some(Array(0x02.toByte))
            case "3" | "0x3" | "0x03" => Some(Array(0x03.toByte))
            case _                    => None
        }

    /*
     * 0 - 4 mapped to specific meanings i.e., DHCP Status Code in RFC 6926
     *
     * The following example represents DHCP Status Code in RFC 6926.
     *
     * Name    Status Code Description
     * ----    ----------- -----------
     * Success         000 Success.  Also signaled by absence of
     *                     a status-code option.
     * UnspecFail      001 Failure, reason unspecified.
     *
     * QueryTerminated 002 Indicates that the server is unable to
     *                     perform a query or has prematurely terminated
     *                     the query for some reason (which should be
     *                     communicated in the text message).
     *
     * MalformedQuery  003 The query was not understood.
     *
     * NotAllowed      004 The query or request was understood but was
     *                     not allowed in this context.
     */
    private[midolman]
    def parse0to4(dhcpValue: String): Option[Array[Byte]] =
        dhcpValue.toCanonicalNumber match {
            case "0" | "0x0" | "0x00" => Some(Array(0x00.toByte))
            case "1" | "0x1" | "0x01" => Some(Array(0x01.toByte))
            case "2" | "0x2" | "0x02" => Some(Array(0x02.toByte))
            case "3" | "0x3" | "0x03" => Some(Array(0x03.toByte))
            case "4" | "0x4" | "0x04" => Some(Array(0x04.toByte))
            case _                    => None
        }

    /*
     * 1 - 8 mapped to specific meanings i.e., DHCP Message Type in RFC 2132
     *
     * The following example represents DHCP Message Type in RFC 2132.
     *
     * Value   Message Type
     * -----   ------------
     *   1     DHCPDISCOVER
     *   2     DHCPOFFER
     *   3     DHCPREQUEST
     *   4     DHCPDECLINE
     *   5     DHCPACK
     *   6     DHCPNAK
     *   7     DHCPRELEASE
     *   8     DHCPINFORM
     */
    private[midolman]
    def parse1to8(dhcpValue: String): Option[Array[Byte]] =
        dhcpValue.toCanonicalNumber match {
            case "1" | "0x1" | "0x01" => Some(Array(0x01.toByte))
            case "2" | "0x2" | "0x02" => Some(Array(0x02.toByte))
            case "3" | "0x3" | "0x03" => Some(Array(0x03.toByte))
            case "4" | "0x4" | "0x04" => Some(Array(0x04.toByte))
            case "5" | "0x5" | "0x05" => Some(Array(0x05.toByte))
            case "6" | "0x6" | "0x06" => Some(Array(0x06.toByte))
            case "7" | "0x7" | "0x07" => Some(Array(0x07.toByte))
            case "8" | "0x8" | "0x08" => Some(Array(0x08.toByte))
            case _                    => None
        }

    private[midolman]
    def parseByte(dhcpValue: String): Option[Array[Byte]] =
        dhcpValue.toCanonicalNumber match {
            case "0" | "0x0" | "0x00" => Some(Array(0x00.toByte))
            case "1" | "0x1" | "0x01" => Some(Array(0x01.toByte))
            case "2" | "0x2" | "0x02" => Some(Array(0x02.toByte))
            case "3" | "0x3" | "0x03" => Some(Array(0x03.toByte))
            case "4" | "0x4" | "0x04" => Some(Array(0x04.toByte))
            case "5" | "0x5" | "0x05" => Some(Array(0x05.toByte))
            case "6" | "0x6" | "0x06" => Some(Array(0x06.toByte))
            case "7" | "0x7" | "0x07" => Some(Array(0x07.toByte))
            case "8" | "0x8" | "0x08" => Some(Array(0x08.toByte))
            case "9" | "0x9" | "0x09" => Some(Array(0x09.toByte))
            case "10" | "0xa" | "0x0a" => Some(Array(0xa.toByte))
            case "11" | "0xb" | "0x0b" => Some(Array(0xb.toByte))
            case "12" | "0xc" | "0x0c" => Some(Array(0xc.toByte))
            case "13" | "0xd" | "0x0d" => Some(Array(0xd.toByte))
            case "14" | "0xe" | "0x0e" => Some(Array(0x0e.toByte))
            case "15" | "0xf" | "0x0f" => Some(Array(0x0f.toByte))
            case "16" | "0x10" => Some(Array(0x10.toByte))
            case "17" | "0x11" => Some(Array(0x11.toByte))
            case "18" | "0x12" => Some(Array(0x12.toByte))
            case "19" | "0x13" => Some(Array(0x13.toByte))
            case "20" | "0x14" => Some(Array(0x14.toByte))
            case "21" | "0x15" => Some(Array(0x15.toByte))
            case "22" | "0x16" => Some(Array(0x16.toByte))
            case "23" | "0x17" => Some(Array(0x17.toByte))
            case "24" | "0x18" => Some(Array(0x18.toByte))
            case "25" | "0x19" => Some(Array(0x19.toByte))
            case "26" | "0x1a" => Some(Array(0x1a.toByte))
            case "27" | "0x1b" => Some(Array(0x1b.toByte))
            case "28" | "0x1c" => Some(Array(0x1c.toByte))
            case "29" | "0x1d" => Some(Array(0x1d.toByte))
            case "30" | "0x1e" => Some(Array(0x1e.toByte))
            case "31" | "0x1f" => Some(Array(0x1f.toByte))
            case _                    => None
        }

    /*
     * Classless routes in RFC 3442
     *
     * Code Len Destination 1    Router 1
     * +-----+---+----+-----+----+----+----+----+----+
     * | 121 | n | d1 | ... | dN | r1 | r2 | r3 | r4 |
     * +-----+---+----+-----+----+----+----+----+----+
     *
     * Destination 2       Router 2
     * +----+-----+----+----+----+----+----+
     * | d1 | ... | dN | r1 | r2 | r3 | r4 |
     * +----+-----+----+----+----+----+----+
     */
    private[midolman]
    def parseClasslessRoutes(dhcpValue: String): Option[Array[Byte]] = try {
        val addrList: Vector[String] = dhcpValue.splitWithComma.toVector
        if (!(addrList.length % 2 == 0)) {
            None
        } else {
            val listBuffer: mutable.ListBuffer[Byte] = mutable.ListBuffer.empty
            var rest: Vector[String] = addrList
            while (rest.nonEmpty) {
                val dstCidr = IPv4Subnet.fromCidr(rest.head)
                val dstAddrs: Array[Byte] = dstCidr.getAddress.toBytes
                val prefixLen: Int = dstCidr.getPrefixLen
                val dstNetmask: Array[Byte] =
                    IPv4Subnet.prefixLenToBytes(prefixLen)
                listBuffer += prefixLen.toByte
                for ((d, m) <- dstAddrs.zip(dstNetmask.takeWhile(_ != 0))) {
                    listBuffer += (d & m).toByte
                }
                val routerAddr = IPv4Addr.stringToBytes(rest.tail.head)
                listBuffer ++= routerAddr
                rest = rest.tail.tail
            }
            Some(listBuffer.toArray)
        }
    } catch {
        case _: IllegalArgumentException => None
    }

    /*
     * Byte followed by IP addresses. i.e., SLP Directory Agent in RFC 2610
     *
     * The following example represents SLP Directory Agent
     *
     * 0                   1                   2                   3
     * 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
     * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
     * |   Code = 78   |    Length     |   Mandatory   |      a1       |
     * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
     * |      a2       |       a3      |       a4      |      ...
     * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
     */
    private[midolman]
    def parseByteFollowedByIpAddrs(
            dhcpValue: String,
            firstByteHandler: SimpleParser): Option[Array[Byte]] = {
        val value: Array[String] = dhcpValue.split(",", 2)
        for {
            mandatory: Array[Byte] <- firstByteHandler(value(0))
            addrs: Array[Byte] <- parseIpAddresses(value(1))
        } yield mandatory ++ addrs
    }

    /*
     * Byte followed by String. i.e., SLP Service Scope in RFC 2610
     *
     * The following example represents SLP Service Scope
     *
     * 0                   1                   2                   3
     * 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
     * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
     * |   Code = 79   |     Length    |   Mandatory   | <Scope List>...
     * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
     */
    private[midolman]
    def parseByteFollowedByString(
            dhcpValue: String,
            firstByteHandler: SimpleParser): Option[Array[Byte]] = {
        val value: Array[String] = dhcpValue.split(",", 2)
        for (mandatory: Array[Byte] <- firstByteHandler(value(0)))
        yield mandatory ++ value(1).trim.getBytes
    }

    private
    def encodeDomain(domain: String,
                     domainMap: Map[String, Short]): (Array[String],
                                                      String, Short) = {
        var i = 0
        do {
            val partial: String = domain.substring(i)
            domainMap.get(partial).foreach { pos =>
                val unmatched = domain.substring(0, (i - 1).max(0))
                return (unmatched.split("\\."), partial, pos)
            }
            i = domain.indexOf(".", i) + 1
        } while ((i > 0) && (i <= domain.length))
        (domain.split("\\."), "", -1)
    }

    /*
     * Encoded domain name in bytes which format is defined in RFC 3397.
     *
     *   https://tools.ietf.org/search/rfc3397
     */
    private [midolman]
    def parseDomainSearchList(dhcpValue: String): Option[Array[Byte]] = {
        val domainMap = mutable.Map.empty[String, Short]
        val domainList: Array[String] = dhcpValue.split(",").map(_.trim)
        val encodedBytes = mutable.ArrayBuffer[Byte]()
        domainList.foreach { domain: String =>
            val (unmatched: Array[String], partialMatched: String, pos: Short) =
                encodeDomain(domain, domainMap.toMap)
            domainMap += (domain -> encodedBytes.length.toShort)
            if (pos >= 0) {
                for (i: Int <- unmatched.indices) {
                    val unmatchedSubset =
                        unmatched.slice(i, unmatched.length).mkString(".")
                    domainMap += ((unmatchedSubset + "." + partialMatched) ->
                        encodedBytes.length.toShort)
                    encodedBytes += unmatched(i).length.toByte
                    encodedBytes ++= unmatched(i).getBytes
                }
                // The first two bits of the two octets represent the offset to
                // the partial domain should be 1 to indicate it's the offset.
                // by the definition in RFC 3397. 0b1100000000000000 can be
                // expressed as 0xc000 and therefore first byte is masked by
                // 0xc0.
                encodedBytes += ((pos >> 8) | 0xc0).toByte
                encodedBytes += pos.toByte
            } else {
                val domainPieces: Array[String] = unmatched
                for (i: Int <- domainPieces.indices) {
                    val domainSubset = domain.split("\\.", i + 1)
                    val partial = domainSubset.last
                    val piece = domainPieces(i)
                    // Ignore TLD.
                    if (i < domainPieces.length - 1) {
                        domainMap += (partial -> encodedBytes.length.toShort)
                    }
                    encodedBytes += piece.length.toByte
                    encodedBytes ++= piece.getBytes
                }
                encodedBytes += 0.toByte
            }
        }
        if (encodedBytes.isEmpty) {
            None
        } else {
            Some(encodedBytes.toArray)
        }
    }

    def parseDhcpOptionValue(code: Byte, value: String): Option[Array[Byte]] = {
        CodeToOption.get(code) match {
            case Some(ipsRequiredCode)
                if Code.IPS_REQUIRED_DHCP_OPTION_CODES.contains(ipsRequiredCode) =>
                parseIpAddresses(value)
            case Some(ipRequiredCode)
                if Code.SINGLE_IP_REQUIRED_DHCP_OPTION_CODES.contains(
                    ipRequiredCode) =>
                parseSingleIpAddress(value)
            case Some(numberRequiredCode)
                if Code.NUMBER_REQUIRED_DHCP_OPTION_CODES.contains(
                    numberRequiredCode) =>
                parseNumbers(value, numberRequiredCode.length)
            case Some(booleanRequiredCode)
                if Code.BOOLEAN_REQUIRED_DHCP_OPTION_CODES.contains(
                    booleanRequiredCode) =>
                parseBoolean(value)
            case Some(cidrRequiredCode)
                if Code.CIDR_REQUIRED_DHCP_OPTION_CODES.contains(
                    cidrRequiredCode) =>
                parseCidr(value)
            case Some(c) if c == Code.NETBIOS_OVER_TCP_IP_NODE_TYPE =>
                parse1248(value)
            case Some(c) if c == Code.OPTION_OVERLOAD =>
                parse1to3(value)
            case Some(c)
                if (c == Code.ERROR_MESSAGE) || (c == Code.DHCP_STATE) =>
                parse1to8(value)
            case Some(c)
                if (c == Code.CLIENT_IDENTIFIER) || (c == Code.DHCP_VSS) =>
                parseByteFollowedByString(value, parseByte)
            case Some(c) if c == Code.SLP_DIRECTORY_AGENT =>
                parseByteFollowedByIpAddrs(value, parseBoolean)
            case Some(c) if c == Code.SLP_SERVICE_SCOPE =>
                parseByteFollowedByString(value, parseBoolean)
            case Some(c) if c == Code.RAPID_COMMIT =>
                Some(Array[Byte](Code.RAPID_COMMIT.value, 0))
            case Some(c) if c == Code.STATUS_CODE =>
                parseByteFollowedByString(value, parse0to4)
            case Some(byteRequiredCode)
                if Code.BYTE_REQUIRED_DHCP_OPTION_CODES.contains(
                    byteRequiredCode) =>
                parseByte(value)
            case Some(c) if c == Code.CLASSLESS_ROUTES =>
                parseClasslessRoutes(value)
            case Some(c) if c == Code.DOMAIN_SEARCH =>
                parseDomainSearchList(value)
            case Some(_) =>
                Some(value.getBytes)
            case _ =>
                None
        }
    }

    def parseDhcpOptionCode(optName: String): Option[Byte] = try {
        Some(optName.trim.toByte)
    } catch {
        case _: java.lang.NumberFormatException =>
            val canonicalName = optName.trim.replaceAll("-", "_").toUpperCase
            NameToCode.get(canonicalName)

    }
}

object DhcpImpl {
    def apply(dhcpCfg: DhcpConfig, inPort: Port, request: DHCP,
              sourceMac: MAC, underlayMtu: Int, configMtu: Int,
              log: Logger) = {
        new DhcpImpl(dhcpCfg, request, sourceMac, underlayMtu, configMtu, log)
            .handleDHCP(inPort)
    }
}

class DhcpException extends Exception {
    override def fillInStackTrace(): Throwable = this
}

object UnsupportedDhcpRequestException extends DhcpException {}
object MalformedDhcpRequestException extends DhcpException {}

/** Configurations required to handle a DHCP request that must be provided based
  * on whatever storage is active.
  */
trait DhcpConfig {
    def bridgeDhcpSubnets(deviceId: UUID): Seq[Subnet]
    def dhcpHost(deviceId: UUID, subnet: Subnet, srcMac: String): Option[Host]
}

class DhcpImpl(val dhcpConfig: DhcpConfig,
               val request: DHCP, val sourceMac: MAC,
               val underlayMtu: Int, val configMtu: Int,
               val log: Logger) {
    import DhcpValueParser._

    private var serverAddr: IPv4Addr = _
    private var serverMac: MAC = _
    private var routerAddr: IPv4Addr = _
    private var yiaddr: IPv4Addr = _
    private var yiAddrMaskLen: Int = 0
    private var opt121Routes: mutable.Buffer[Opt121] = _
    private var dnsServerAddrsBytes: List[Array[Byte]] = Nil
    private var interfaceMtu: Int = 0

    def handleDHCP(port: Port) : Option[Ethernet] = {
        // These fields are decided based on the port configuration.
        // DHCP is handled differently for bridge and router ports.

        log.debug("Got a DHCP request")
        // Get the RCU port object and start simulation.
        port match {
            case p: BridgePort if p.isExterior =>
                log.debug("Handle DHCP request arriving on bridge port.")
                dhcpFromBridgePort(p)
            case _ =>
                // We don't handle this, but don't throw an error.
                log.info(s"Got a DHCP request on port ${port.id} which is not " +
                          " a bridge exterior port")
                None
        }
    }

    private type HostAndSubnetOptPair = (Option[Host], Option[Subnet])

    private
    def getHostAndAssignedSubnet(port: BridgePort): HostAndSubnetOptPair = {
        // TODO(pino): use an async API
        val subnets = dhcpConfig.bridgeDhcpSubnets(port.deviceId)

        // Look for the DHCP's source MAC in the list of hosts in each subnet
        var host: Option[Host] = None
        val assignment = subnets.find { subnet =>
            log.debug("Looking up assignment for MAC {} on subnet {} ",
                      sourceMac, subnet.getId)
            if (subnet.getServerAddr == null) {
                subnet.setServerAddr(IPv4Addr.fromString("0.0.0.0"))
            }
            host = dhcpConfig.dhcpHost(port.deviceId, subnet,
                                       sourceMac.toString)
            host.isDefined && (host.get.getIp != null)
        }
        (host, assignment)
    }

    private def dhcpFromBridgePort(port: BridgePort): Option[Ethernet] = {
        getHostAndAssignedSubnet(port) match {
            case (Some(host: Host), Some(sub: Subnet)) =>
                log.debug(s"Found DHCP static assignment for MAC $sourceMac => " +
                          s"${host.getName} @ ${host.getIp}")

                // TODO(pino): the server MAC should be in configuration.
                serverMac = MAC.fromString("02:a8:9c:de:39:27")
                serverAddr = sub.getServerAddr
                routerAddr = sub.getDefaultGateway
                yiaddr = host.getIp
                yiAddrMaskLen = sub.getSubnetAddr.getPrefixLen

                dnsServerAddrsBytes =
                    Option(sub.getDnsServerAddrs).map{ _.toList}.getOrElse(Nil)
                        .map { _.toBytes }
                opt121Routes = sub.getOpt121Routes

                // NOTES on MTU:
                // - We should never send a DHCP offer MTU option higher than the underlayMtu.
                // - Subnet mtu takes precedence over global configuration

                (sub.getInterfaceMTU match {
                    case 0 =>
                        Some(Math.min(configMtu, underlayMtu).toShort)
                    case subnetMtu: Short =>
                        Some(Math.min(subnetMtu, underlayMtu).toShort)
                }) match {
                    case Some(minMtu) =>
                        interfaceMtu = minMtu
                        log.debug(s"Building DHCP reply for MAC $sourceMac " +
                                  s"with MTU $interfaceMtu")
                        makeDhcpReply(port, host)
                    case _ =>
                        interfaceMtu = 0
                        log.warn("Failed to calculate interface MTU, cannot " +
                                 "build DHCP reply")
                        None
                }
            case _ =>
                log.debug("No static DHCP assignment for MAC {}", sourceMac)
                None
        }
    }

    private def opt121ToByteArray(opt121: Opt121): mutable.ListBuffer[Byte] = {
        val bytes = mutable.ListBuffer[Byte]()
        // First append the destination subnet's maskLength
        val maskLen = opt121.getRtDstSubnet.getPrefixLen.toByte
        bytes.append(maskLen)
        // Now append the significant octets of the subnet.
        val dstBytes = opt121.getRtDstSubnet.getAddress.toBytes
        if (maskLen > 0) bytes.append(dstBytes(0))
        if (maskLen > 8) bytes.append(dstBytes(1))
        if (maskLen > 16) bytes.append(dstBytes(2))
        if (maskLen > 24) bytes.append(dstBytes(3))
        // Now append the 4 octets of the gateway.
        val gwBytes = opt121.getGateway.toBytes
        bytes.appendAll(gwBytes.toList)
        bytes
    }

    private def makeDhcpReply(port: BridgePort,
                              host: Host): Option[Ethernet] = {
        val chaddr = request.getClientHardwareAddress
        if (null == chaddr) {
            log.warn("Dropping DHCP request with null hw addr")
            return None
        }
        if (chaddr.length != 6) {
            log.warn(s"Dropping DHCP request with hw addr length ${chaddr.length} greater than 6.")
            return None
        }
        log.debug(s"Got DHCP request on port ${port.id} with "+
                  s"hw addr ${MAC.bytesToString(chaddr)} and "+
                  s"ip addr ${request.getClientIPAddress}")

        // Extract all the options and put them in a map
        val reqOptions = mutable.HashMap[Byte, DHCPOption]()
        val requestedCodes = mutable.Set[Byte]()
        request.getOptions foreach { opt =>
            val code = opt.getCode
            reqOptions.put(code, opt)
            log.debug(s"found option $code:${DHCPOption.CODE_TO_NAME.get(code)}")
            code match {
                case v if v == DHCPOption.Code.DHCP_TYPE.value =>
                    if (opt.getLength != 1) {
                        log.warn("Dropping DHCP request, "
                            + "dhcp msg type option has bad length or data.")
                        throw MalformedDhcpRequestException
                    }
                    val msgType = opt.getData()(0)
                    log.debug("dhcp msg type "+
                        s"$msgType:${DHCPOption.msgTypeToName.get(msgType)}")
                case v if v == DHCPOption.Code.PRM_REQ_LIST.value =>
                    if (opt.getLength <= 0) {
                        log.warn("Dropping DHCP request, "
                            + "param request list has bad length")
                        throw MalformedDhcpRequestException
                    }
                    opt.getData foreach { c =>
                        requestedCodes.add(c)
                        log.debug(s"DHCP client requested option "+
                                  s"$c:${DHCPOption.CODE_TO_NAME.get(c)}")
                    }
                case _ => // Do nothing
            }
        }

        // Use mutable.HashMap to eliminate the duplication between the Midolman
        // DHCP extra option handlings and the Neutron ones.
        val optionMap = mutable.HashMap[Byte, DHCPOption]()
        val typeOpt = reqOptions.get(DHCPOption.Code.DHCP_TYPE.value)
        if (typeOpt == None) {
            log.warn("Dropping DHCP request, no dhcp msg type found")
            throw MalformedDhcpRequestException
        }

        typeOpt.get.getData()(0) match {
            case v if v == DHCPOption.MsgType.DISCOVER.value =>
                log.debug("Received a DHCP Discover message")
                // Reply with a dchp OFFER.
                optionMap.put(DHCPOption.Code.DHCP_TYPE.value,
                    new DHCPOption(
                        DHCPOption.Code.DHCP_TYPE.value,
                        DHCPOption.Code.DHCP_TYPE.length,
                        Array[Byte](DHCPOption.MsgType.OFFER.value)))
            case v if v == DHCPOption.MsgType.REQUEST.value =>
                log.debug("Received a DHCP Request message")
                // Reply with a dchp ACK.
                optionMap.put(DHCPOption.Code.DHCP_TYPE.value,
                    new DHCPOption(
                        DHCPOption.Code.DHCP_TYPE.value,
                        DHCPOption.Code.DHCP_TYPE.length,
                        Array[Byte](DHCPOption.MsgType.ACK.value)))
                // http://tools.ietf.org/html/rfc2131 Section 3.1, Step 3:
                // "The client broadcasts a DHCPREQUEST message that MUST include
                // the 'server identifier' option to indicate which server is has
                // selected."
                // TODO(pino): figure out why Linux doesn't send us the server id
                // and try re-enabling this code.
                reqOptions.get(DHCPOption.Code.SERVER_ID.value) match {
                    case None =>
                        log.debug("No DHCP server id option found.")
                        // TODO(pino): return Future.successful(false)?
                    case Some(opt) =>
                        // The server id should correspond to this port's address.
                        val theirServId = IPv4Addr.bytesToInt(opt.getData)
                        if (serverAddr.addr != theirServId) {
                            log.warn("Dropping DHCP request, client chose server "+
                                s"${IPv4Addr.intToString(theirServId)} not us $serverAddr")
                        }
                }
                // The request must contain a requested IP address option.
                reqOptions.get(DHCPOption.Code.REQUESTED_IP.value) match {
                    case None =>
                        log.debug("No requested DHCP ip option found.")
                        //return Promise.failed(new Exception(
                        //    "DHCP message with no requested-IP option."))
                    case Some(opt) =>
                        // The requested ip must correspond to the yiaddr in our offer.
                        val reqIp = IPv4Addr.bytesToInt(opt.getData)
                        // TODO(pino): must keep state and remember the offered ip based
                        // on the chaddr or the client id option.
                        if (yiaddr.addr != reqIp) {
                            log.debug("Sending DHCP NACK: the requested ip "+
                                s"$reqIp does not match what we offered ($yiaddr)")
                            // Overwrite the default ACK with a dhcp NACK
                            optionMap.put(
                                DHCPOption.Code.DHCP_TYPE.value,
                                new DHCPOption(
                                    DHCPOption.Code.DHCP_TYPE.value,
                                    DHCPOption.Code.DHCP_TYPE.length,
                                    Array[Byte](DHCPOption.MsgType.NAK.value)))
                        }
                }
            case msgType =>
                log.warn("Dropping DHCP request, unsupported msg type "+
                         s"$msgType:${DHCPOption.msgTypeToName.get(msgType)}")
                throw UnsupportedDhcpRequestException
        }

        val reply = new DHCP
        reply.setOpCode(DHCP.OPCODE_REPLY)
        reply.setTransactionId(request.getTransactionId)
        reply.setHardwareAddressLength(6)
        reply.setHardwareType(ARP.HW_TYPE_ETHERNET.toByte)
        reply.setClientHardwareAddress(sourceMac)
        reply.setServerIPAddress(serverAddr.addr)
        reply.setYourIPAddress(yiaddr.addr)

        // TODO(pino): do we need to include the DNS option?
        optionMap.put(DHCPOption.Code.MASK.value,
            new DHCPOption(DHCPOption.Code.MASK.value,
                DHCPOption.Code.MASK.length,
                IPv4Addr.intToBytes(
                    ~0 << (32 - yiAddrMaskLen))))

        // Generate the broadcast address... this is nwAddr with 1's in the
        // last 32-nwAddrLength bits.
        val mask = ~0 >>> yiAddrMaskLen
        val bcast = mask | yiaddr.addr
        log.debug("Setting DHCP bcast addr option to {}", IPv4Addr.intToString(bcast))
        optionMap.put(DHCPOption.Code.BCAST_ADDR.value,
            new DHCPOption(DHCPOption.Code.BCAST_ADDR.value,
                DHCPOption.Code.BCAST_ADDR.length,
                IPv4Addr.intToBytes(bcast)))
        optionMap.put(DHCPOption.Code.IP_LEASE_TIME.value,
            new DHCPOption(DHCPOption.Code.IP_LEASE_TIME.value,
                DHCPOption.Code.IP_LEASE_TIME.length,
                IPv4Addr.intToBytes((1 day).toSeconds.toInt)))
        optionMap.put(DHCPOption.Code.INTERFACE_MTU.value,
            new DHCPOption(DHCPOption.Code.INTERFACE_MTU.value,
                DHCPOption.Code.INTERFACE_MTU.length,
                Array[Byte](((interfaceMtu >> 8) & 0xff).toByte,
                            (interfaceMtu & 0xff).toByte)))
        if (routerAddr != null) {
            optionMap.put(DHCPOption.Code.ROUTER.value,
                new DHCPOption(DHCPOption.Code.ROUTER.value,
                    DHCPOption.Code.ROUTER.length,
                    routerAddr.toBytes))
        }
        // in MidoNet the DHCP server is the same as the router
        optionMap.put(DHCPOption.Code.SERVER_ID.value,
            new DHCPOption(
                DHCPOption.Code.SERVER_ID.value,
                DHCPOption.Code.SERVER_ID.length,
                serverAddr.toBytes))

        if (dnsServerAddrsBytes.nonEmpty) {
            val len = 4 * dnsServerAddrsBytes.length
            val buffer = ByteBuffer.allocate(len)
            dnsServerAddrsBytes.foreach{ bytes => buffer put bytes}
            optionMap.put(DHCPOption.Code.DNS.value,
                new DHCPOption(DHCPOption.Code.DNS.value,
                    len.toByte, buffer.array))
        }
        // If there are classless static routes, add the option.
        if (null != opt121Routes && opt121Routes.length > 0) {
            val bytes = mutable.ListBuffer[Byte]()
            if (routerAddr != null) {
                // According to RFC 3442, if classless routes (option 121) are
                // being provided, then the router option should be ignored.
                // In this case we want to provide the default route with
                // option 121 in addition to option 3.
                val opt121DefaultRoute = new Opt121()
                val univSubnet = IPv4Subnet.fromCidr("0.0.0.0/0")

                opt121DefaultRoute.setGateway(routerAddr)
                opt121DefaultRoute.setRtDstSubnet(univSubnet)

                log.debug("Found router address. Adding default route to "
                          + opt121DefaultRoute)
                opt121Routes += opt121DefaultRoute
            }
            opt121Routes foreach { rt =>
                log.debug("Found classless route {}", rt)
                bytes.appendAll(opt121ToByteArray(rt))
            }
            log.debug("Adding Option 121 (classless static routes) with " +
                      s"${opt121Routes.length} routes")
            // Finally, construct the classless static routes option
            optionMap.put(DHCPOption.Code.CLASSLESS_ROUTES.value,
                new DHCPOption(
                    DHCPOption.Code.CLASSLESS_ROUTES.value(),
                    bytes.length.toByte,
                    bytes.toArray))
        }

        // Add extra DHCP options. This overwrite the existing DHCP extra option
        // set already before calling this method.
        setExtraDhcpOptions(host, optionMap)

        val options: mutable.ListBuffer[DHCPOption] =
            optionMap.values.to[mutable.ListBuffer]
        // And finally add the END option.
        options.add(new DHCPOption(DHCPOption.Code.END.value,
            DHCPOption.Code.END.length, null))
        reply.setOptions(options)

        val udp = new UDP
        udp.setSourcePort(67)
        udp.setDestinationPort(68)
        udp.setPayload(reply)

        val ip = new IPv4
        ip.setSourceAddress(serverAddr.addr)
        ip.setDestinationAddress("255.255.255.255")
        ip.setProtocol(UDP.PROTOCOL_NUMBER)
        ip.setPayload(udp)

        val eth = new Ethernet
        eth.setEtherType(IPv4.ETHERTYPE)
        eth.setPayload(ip)

        eth.setSourceMACAddress(serverMac)
        eth.setDestinationMACAddress(sourceMac)

        Some(eth)
    }

    private
    def setExtraDhcpOptions(host: Host,
                            optMap: mutable.Map[Byte, DHCPOption]): Unit =
        for (opt <- host.getExtraDhcpOpts if host != null) {
            val dhcpOptOption: Option[DHCPOption] = for {
                code <- parseDhcpOptionCode(opt.optName)
                option <- CodeToOption.get(code)
                value <- parseDhcpOptionValue(code, opt.optValue)
                if (value.length != 0) &&
                    (value.length % option.length == 0)
            } yield new DHCPOption(
                    code, value.length.toByte, value)
            if (dhcpOptOption.isDefined) {
                log.debug(s"Add extra DHCP Option ${opt.optName} " +
                    s"with value ${opt.optValue}")
                val dhcpOption = dhcpOptOption.get
                optMap.put(dhcpOption.getCode, dhcpOption)
            } else {
                log.info(s"Invalid DHCP Option: ${opt.optName} " +
                    s"with value ${opt.optValue}")
                log.info("This invalid option will be treated as " +
                    "UNKNOWN")
            }
        }
}
