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

import java.nio.ByteBuffer
import scala.collection.JavaConversions._
import scala.collection.mutable
import scala.concurrent.duration._

import com.typesafe.scalalogging.Logger

import org.midonet.cluster.DataClient
import org.midonet.cluster.data.dhcp.{Subnet, Host, Opt121}
import org.midonet.midolman.topology.devices.{RouterPort, BridgePort, Port}
import org.midonet.packets._

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

    private def dhcpFromBridgePort(port: BridgePort): Option[Ethernet] = {
        // TODO(pino): use an async API
        val subnets = dataClient.dhcpSubnetsGetByBridgeEnabled(port.deviceId)

        // Look for the DHCP's source MAC in the list of hosts in each subnet
        var host: Host = null

        val assignment = subnets.find { sub =>
            log.debug("Looking up assignment for MAC {} on subnet {} ",
                      sourceMac, sub.getId)
            if (sub.isReplyReady) {
                // TODO(pino): make this asynchronous?
                host = dataClient.dhcpHostsGet(port.deviceId,
                                               sub.getSubnetAddr,
                                               sourceMac.toString)
                (host != null) && (host.getIp != null)
            } else {
                log.warn("Can not create DHCP reply because the subnet" +
                         s" ${sub.getId} does not have all necessary information.")
                false
            }
        }
        assignment match {
            case Some(sub: Subnet) =>
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
                        makeDhcpReply(port)
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

    private def makeDhcpReply(port: BridgePort): Option[Ethernet] = {

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

        return Some(eth)
    }
}
