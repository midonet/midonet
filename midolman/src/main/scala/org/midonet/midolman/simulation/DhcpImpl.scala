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
package org.midonet.midolman.simulation

import java.nio.{BufferOverflowException, ByteBuffer}
import scala.collection.JavaConversions._
import scala.collection.mutable
import scala.concurrent.duration._

import com.typesafe.scalalogging.Logger
import org.apache.commons.net.util.SubnetUtils

import org.midonet.cluster.DataClient
import org.midonet.cluster.client._
import org.midonet.cluster.data.dhcp.{Subnet, Host, Opt121}
import org.midonet.packets._

/**
 * DHCP option value parser based on RFC 2132
 *   https://www.ietf.org/rfc/rfc2132.txt
 */
object DhcpValueParser {
    import DHCPOption._

    val DhcpCodes = DHCPOption.Code.values
    val OctetsInInt = java.lang.Integer.SIZE / java.lang.Byte.SIZE

    private[midolman]
    implicit class DhcpValueString(val s: String) {
        def splitWithComma: Array[String] =
            s.replaceAll("\\s", "").split(",")
    }

    private[midolman]
    def parseIpAddresses(dhcpValue: String): Option[Array[Byte]] = try {
        Some((for (ipString <- dhcpValue.splitWithComma)
        yield IPv4Addr.stringToBytes(ipString)).flatten)
    } catch {
        case _: IllegalArgumentException => None
    }

    private[midolman]
    def parseNumbers(dhcpValue: String): Option[Array[Byte]] = try {
        val numbers = dhcpValue.splitWithComma.map(_.toInt)
        val buff = ByteBuffer.allocate(OctetsInInt * numbers.length)
        numbers.foreach(buff.putInt)
        Some(buff.array())
    } catch {
        case _: NumberFormatException | _: BufferOverflowException => None
    }

    private[midolman]
    def parseBoolean(dhcpValue: String): Option[Array[Byte]] = try {
        Some(if (dhcpValue.trim.toBoolean) Array(1.toByte) else Array(0.toByte))
    } catch {
        case _: IllegalArgumentException =>
            dhcpValue.trim match {
                case "0" | "0x0" | "0x00" => Some(Array(0.toByte))
                case "1" | "0x1" | "0x01" => Some(Array(1.toByte))
                case _                    => None
            }
    }

    private[midolman]
    def parseCidr(dhcpValue: String): Option[Array[Byte]] = try {
        Some((for {
            cidrString <- dhcpValue.splitWithComma
            cidr = new SubnetUtils(cidrString).getInfo
            networkAddress = IPv4Addr.fromString(cidr.getNetworkAddress)
            netmask = IPv4Addr.fromString(cidr.getNetmask)
        } yield networkAddress.toBytes ++ netmask.toBytes).flatten)
    } catch {
        case _: IllegalArgumentException => None
    }

    /*
     * 8.7. NetBIOS over TCP/IP Node Type Option in RFC 2132
     *
     * Value         Node Type
     * -----         ---------
     * 0x1           B-node
     * 0x2           P-node
     * 0x4           M-node
     * 0x8           H-node
     */
    private[midolman]
    def parseNetBiosTcpIpNodeType(dhcpValue: String): Option[Array[Byte]] =
        dhcpValue.trim match {
            case "1" | "0x1" | "0x01" => Some(Array(0x01.toByte))
            case "2" | "0x2" | "0x02" => Some(Array(0x02.toByte))
            case "4" | "0x4" | "0x04" => Some(Array(0x04.toByte))
            case "8" | "0x8" | "0x08" => Some(Array(0x08.toByte))
            case _                    => None
        }

    /*
     * 9.3. Option Overload in RFC 2132
     *
     * Value   Meaning
     * -----   --------
     *   1     the 'file' field is used to hold options
     *   2     the 'sname' field is used to hold options
     *   3     both fields are used to hold options
     */
    private[midolman]
    def parseOptionOverload(dhcpValue: String): Option[Array[Byte]] =
        dhcpValue.trim match {
            case "1" | "0x1" | "0x01" => Some(Array(0x01.toByte))
            case "2" | "0x2" | "0x02" => Some(Array(0x02.toByte))
            case "3" | "0x3" | "0x03" => Some(Array(0x03.toByte))
            case _                    => None
        }

    /*
     * 9.6. DHCP Message Type in RFC 2132
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
    def parseDhcpMessageType(dhcpValue: String): Option[Array[Byte]] =
        dhcpValue.trim match {
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

    /*
     * 9.14. Client-identifier in RFC 3315 / 4361 / 6842
     *
     *   Code   Len   Type  Client-Identifier
     * +-----+-----+-----+-----+-----+---
     * |  61 |  n  |  t1 |  i1 |  i2 | ...
     * +-----+-----+-----+-----+-----+---
     */
    private[midolman]
    def parseClientIdentifier(dhcpValue: String): Option[Array[Byte]] =
        dhcpValue.splitWithComma match {
            case a: Array[String] if a.length >= 2 =>
                val typeOption: Option[Byte] =  try {
                    Some(a.head.toInt.toByte)
                } catch {
                    case _: NumberFormatException => None
                }
                val clientIdentifierOption: Option[Array[Byte]] = a.tail match {
                    case Array(s: String) => Some(s.getBytes)
                    case _                => None
                }
                for {
                    t <- typeOption
                    clientIdentifier <- clientIdentifierOption
                } yield t +: clientIdentifier
            case _ => None
        }

    def parseDhcpOptionValue(code: Byte, value: String): Option[Array[Byte]] = {
        DhcpCodes.find(_.value == code) match {
            case Some(ipRequiredCode)
                if IP_REQUIRED_DHCP_OPTION_CODES.contains(ipRequiredCode) =>
                parseIpAddresses(value)
            case Some(numberRequiredCode)
                if NUMBER_REQUIRED_DHCP_OPTION_CODES.contains(
                    numberRequiredCode) =>
                parseNumbers(value)
            case Some(booleanRequiredCode)
                if BOOLEAN_REQUIRED_DHCP_OPTION_CODES.contains(
                    booleanRequiredCode) =>
                parseBoolean(value)
            case Some(cidrRequiredCode)
                if CIDR_REQUIRED_DHCP_OPTION_CODES.contains(cidrRequiredCode) =>
                parseCidr(value)
            case Some(c) if c == Code.NETBIOS_OVER_TCP_IP_NODE_TYPE =>
                parseNetBiosTcpIpNodeType(value)
            case Some(c) if c == Code.OPTION_OVERLOAD =>
                parseOptionOverload(value)
            case Some(c) if c == Code.MESSAGE =>
                parseDhcpMessageType(value)
            case Some(c) if c == Code.CLIENT_IDENTIFIER =>
                parseClientIdentifier(value)
            case Some(_) =>
                Some(value.getBytes)
            case _ =>
                None
        }
    }
}

object DhcpImpl {
    def apply(dataClient: DataClient, inPort: Port, request: DHCP,
              sourceMac: MAC, mtu: Option[Short], log: Logger) =
        new DhcpImpl(dataClient, request, sourceMac, mtu, log).handleDHCP(inPort)
}

class DhcpException extends Exception {
    override def fillInStackTrace(): Throwable = this
}

object UnsupportedDhcpRequestException extends DhcpException {}
object MalformedDhcpRequestException extends DhcpException {}

class DhcpImpl(val dataClient: DataClient,
               val request: DHCP, val sourceMac: MAC,
               val mtu: Option[Short], val log: Logger) {
    import DhcpValueParser._

    private var serverAddr: IPv4Addr = null
    private var serverMac: MAC = null
    private var routerAddr: IPv4Addr = null
    private var yiaddr: IPv4Addr = null
    private var yiAddrMaskLen: Int = 0
    private var opt121Routes: mutable.Seq[Opt121] = null
    private var dnsServerAddrsBytes: List[Array[Byte]] = Nil

    private var interfaceMTU : Short = 0

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
        val subnets = dataClient.dhcpSubnetsGetByBridgeEnabled(port.deviceID)
        // Look for the DHCP's source MAC in the list of hosts in each subnet
        var host: Option[Host] = None
        val assignment = subnets.find { sub =>
            log.debug("Looking up assignment for MAC {} on subnet {} ",
                      sourceMac, sub.getId)
            if (sub.isReplyReady) {
                // TODO(pino): make this asynchronous?
                host = Option(dataClient.dhcpHostsGet(port.deviceID,
                                                    sub.getSubnetAddr,
                                                    sourceMac.toString))
                host.isDefined && (host.get.getIp != null)
            } else {
                log.warn("Can not create DHCP reply because the subnet" +
                         s" ${sub.getId} does not have all necessary " +
                         "information.")
                false
            }
        }
        (host, assignment)
    }

    private def dhcpFromBridgePort(port: BridgePort): Option[Ethernet] = {
        getHostAndAssignedSubnet(port) match {
            case (Some(host: Host), Some(sub: Subnet)) =>
                log.debug(s"Found DHCP static assignment for MAC $sourceMac => "+
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

                (sub.getInterfaceMTU match {
                    case 0 => mtu
                    case s: Short => Some(s)
                }) match {
                    case Some(mtu) =>
                        interfaceMTU = mtu
                        log.debug(s"Building DHCP reply for MAC $sourceMac with MTU $mtu")
                        makeDhcpReply(port, host)
                    case _ =>
                        interfaceMTU = 0
                        log.warn("Failed to calculate interface MTU, cannot " +
                                 "build DHCP reply")
                        None
                }
            case _ =>
                log.debug("No static DHCP assignment for MAC {}", sourceMac)
                None
        }
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
            log.debug(s"found option $code:${DHCPOption.codeToName.get(code)}")
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
                                  s"$c:${DHCPOption.codeToName.get(c)}")
                    }
                case _ => // Do nothing
            }
        }
        val options = mutable.ListBuffer[DHCPOption]()
        val typeOpt = reqOptions.get(DHCPOption.Code.DHCP_TYPE.value)
        if (typeOpt == None) {
            log.warn("Dropping DHCP request, no dhcp msg type found")
            throw MalformedDhcpRequestException
        }

        typeOpt.get.getData()(0) match {
            case v if v == DHCPOption.MsgType.DISCOVER.value =>
                log.debug("Received a DHCP Discover message")
                // Reply with a dchp OFFER.
                options.add(new DHCPOption(
                    DHCPOption.Code.DHCP_TYPE.value,
                    DHCPOption.Code.DHCP_TYPE.length,
                    Array[Byte](DHCPOption.MsgType.OFFER.value)))
            case v if v == DHCPOption.MsgType.REQUEST.value =>
                log.debug("Received a DHCP Request message")
                // Reply with a dchp ACK.
                options.add(new DHCPOption(
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
                            log.warn("Dropping DHCP request: the requested ip "+
                                s"$reqIp does not match what we offered ($yiaddr)")
                            // TODO(pino): send a dhcp NAK reply.
                            return None
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
        options.add(new DHCPOption(DHCPOption.Code.MASK.value,
                                   DHCPOption.Code.MASK.length,
                                   IPv4Addr.intToBytes(
                                       ~0 << (32 - yiAddrMaskLen))))

        // Generate the broadcast address... this is nwAddr with 1's in the
        // last 32-nwAddrLength bits.
        val mask = ~0 >>> yiAddrMaskLen
        val bcast = mask | yiaddr.addr
        log.debug("Setting DHCP bcast addr option to {}", IPv4Addr.intToString(bcast))
        options.add(new DHCPOption(DHCPOption.Code.BCAST_ADDR.value,
                                   DHCPOption.Code.BCAST_ADDR.length,
                                   IPv4Addr.intToBytes(bcast)))
        options.add(new DHCPOption(DHCPOption.Code.IP_LEASE_TIME.value,
                                   DHCPOption.Code.IP_LEASE_TIME.length,
                                   IPv4Addr.intToBytes((1 day).toSeconds.toInt)))
        options.add(new DHCPOption(DHCPOption.Code.INTERFACE_MTU.value,
                                   DHCPOption.Code.INTERFACE_MTU.length,
                                   Array[Byte]((interfaceMTU/256).toByte,
                                               (interfaceMTU%256).toByte)))
        if (routerAddr != null) {
            options.add(new DHCPOption(DHCPOption.Code.ROUTER.value,
                                       DHCPOption.Code.ROUTER.length,
                                       routerAddr.toBytes))
        }
        // in MidoNet the DHCP server is the same as the router
        options.add(new DHCPOption(
            DHCPOption.Code.SERVER_ID.value,
            DHCPOption.Code.SERVER_ID.length,
            serverAddr.toBytes))

        if (dnsServerAddrsBytes.nonEmpty) {
            val len = 4 * dnsServerAddrsBytes.length
            val buffer = ByteBuffer.allocate(len)
            dnsServerAddrsBytes.foreach{ bytes => buffer put bytes}
            options.add(new DHCPOption(DHCPOption.Code.DNS.value,
                                       len.toByte, buffer.array))
        }
        // If there are classless static routes, add the option.
        if (null != opt121Routes && opt121Routes.length > 0) {
            val bytes = mutable.ListBuffer[Byte]()
            opt121Routes foreach { rt =>
                log.debug("Found classless route {}", rt)
                // First append the destination subnet's maskLength
                val maskLen = rt.getRtDstSubnet.getPrefixLen.toByte
                bytes.append(maskLen)
                // Now append the significant octets of the subnet.
                val dstBytes = rt.getRtDstSubnet.getAddress.toBytes
                if (maskLen > 0) bytes.append(dstBytes(0))
                if (maskLen > 8) bytes.append(dstBytes(1))
                if (maskLen > 16) bytes.append(dstBytes(2))
                if (maskLen > 24) bytes.append(dstBytes(3))
                // Now append the 4 octets of the gateway.
                val gwBytes = rt.getGateway.toBytes
                bytes.appendAll(gwBytes.toList)
            }
            log.debug("Adding Option 121 (classless static routes) with " +
                      s"${opt121Routes.length} routes")
            // Finally, construct the classless static routes option
            options.add(new DHCPOption(
                DHCPOption.Code.CLASSLESS_ROUTES.value(),
                bytes.length.toByte,
                bytes.toArray))
        }
        // Add extra DHCP options
        options ++= getExtraOptions(host)
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
    def getExtraOptions(host: Host): mutable.ListBuffer[DHCPOption] = {
        val extraOptions = mutable.ListBuffer[DHCPOption]()
        val allDhcpOptions: Array[DHCPOption.Code] =
            DHCPOption.Code.values
        for (opt <- host.getExtraDhcpOpts if host != null) {
            val code = opt.optName.toByte
            val dhcpOptOption: Option[DHCPOption] = for {
                option <- allDhcpOptions.find(_.value == code)
                value <- parseDhcpOptionValue(code, opt.optValue)
                if (value.length != 0) &&
                    (value.length % option.length == 0)
            } yield new DHCPOption(
                    code, value.length.toByte, value)
            if (dhcpOptOption.isDefined) {
                log.debug(s"Add extra DHCP Option ${opt.optName} " +
                    s"with value ${opt.optValue}")
                extraOptions.add(dhcpOptOption.get)
            } else {
                log.info(s"Invalid DHCP Option: ${opt.optName} " +
                    s"with value ${opt.optValue}")
                log.info("This invalid option will be treated as " +
                    "UNKNOWN")
            }
        }
        extraOptions
    }
}
