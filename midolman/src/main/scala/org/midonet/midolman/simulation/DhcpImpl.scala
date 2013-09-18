/*
 * Copyright 2012 Midokura Europe SARL
 */

package org.midonet.midolman.simulation

import scala.compat.Platform
import akka.actor.ActorSystem
import akka.dispatch.{ExecutionContext, Future, Promise}
import akka.pattern.ask
import akka.util.duration._
import akka.actor.{ActorContext, ActorSystem}
import akka.pattern.ask
import akka.util.duration._
import collection.JavaConversions._
import collection.mutable
import java.util.UUID

import org.midonet.cluster.DataClient
import org.midonet.cluster.client._
import org.midonet.midolman.{DeduplicationActor, DatapathController, FlowController}
import org.midonet.midolman.host.interfaces.InterfaceDescription
import org.midonet.midolman.topology.VirtualTopologyActor.{expiringAsk, PortRequest}
import org.midonet.midolman.DeduplicationActor.EmitGeneratedPacket
import org.midonet.packets._
import org.midonet.midolman.DatapathController.LocalTunnelInterfaceInfo
import org.midonet.cluster.data.dhcp.Opt121
import org.midonet.cluster.data.TunnelZone
import org.midonet.cluster.data.zones.{GreTunnelZone, CapwapTunnelZone, IpsecTunnelZone}
import org.midonet.midolman.state.StateAccessException


class DhcpImpl(val dataClient: DataClient, val inPortId: UUID,
                  val request: DHCP, val sourceMac: MAC,
                  val cookie: Option[Int])
                 (implicit val ec: ExecutionContext,
                  val actorSystem: ActorSystem,
                  val actorContext: ActorContext) {
    private val log = akka.event.Logging(actorSystem, this.getClass)
    private val flowController = FlowController.getRef(actorSystem)
    private val datapathController = DatapathController.getRef(actorSystem)

    private var serverAddr: IntIPv4 = null
    private var serverMac: MAC = null
    private var routerAddr: IntIPv4 = null
    private var yiaddr: IntIPv4 = null
    private var opt121Routes: mutable.Seq[Opt121] = null;
    private var dnsServerAddrs : mutable.Seq[IntIPv4] = null;
    private var interfaceMTU : Short = 0

    def handleDHCP : Future[Boolean] = {
        // These fields are decided based on the port configuration.
        // DHCP is handled differently for bridge and router ports.

        log.debug("Got a DHCP request");
        // Get the RCU port object and start simulation.
        expiringAsk(PortRequest(inPortId, false)).mapTo[Port[_]].flatMap {
            port: Port[_] => port match {
                case p: BridgePort if p.isExterior=>
                    log.debug("Handle DHCP request arriving on bridge port.")
                    dhcpFromBridgePort(p)
                case p: RouterPort if p.isExterior=>
                    // We still don't handle DHCP on router ports.
                    log.debug("Don't handle DHCP arriving on a router port.")
                    Promise.successful(false)
                case _ =>
                    // We don't handle this, but don't throw an error.
                    log.error("Don't expect to be invoked for DHCP packets " +
                        "arriving anywhere but bridge/router exterior ports")
                    Promise.successful(false)
            }
        }
    }

    private def dhcpFromBridgePort(port: BridgePort): Future[Boolean] =
    {
        // TODO(pino): use an async API
        val subnets = dataClient.dhcpSubnetsGetByBridge(port.deviceID)
        // Look for the DHCP's source MAC in the list of hosts in each subnet
        for (sub <- subnets) {
            log.debug("Look for mac {} in static assignments for " +
                "DhcpSubnet {}", sourceMac, sub)
            try {
                // TODO(pino): make this asynchronous?
                val host = dataClient.dhcpHostsGet(
                    port.deviceID, sub.getSubnetAddr, sourceMac.toString)
                if (host != null) {
                    log.debug("Found DHCP static assignment for mac {} => {}",
                        sourceMac, host)
                    serverAddr = sub.getServerAddr
                    // TODO(pino): the server MAC should be in configuration.
                    serverMac = MAC.fromString("02:a8:9c:de:39:27")
                    routerAddr = sub.getDefaultGateway
                    yiaddr = host.getIp.clone.setMaskLength(
                        sub.getSubnetAddr.getMaskLength)
                    if (sub.getDnsServerAddrs != null)
                        dnsServerAddrs = sub.getDnsServerAddrs
                    opt121Routes = sub.getOpt121Routes
                    interfaceMTU = sub.getInterfaceMTU
                    if (interfaceMTU == 0) {
                        var interfaceMTUFuture : Future[Short] = null
                        interfaceMTUFuture = calculateInterfaceMTU
                        return (interfaceMTUFuture flatMap {
                            case mtu =>
                                if (mtu == 0) {
                                    log.error("Fail to calculate interface MTU")
                                    Promise.successful(false)
                                } else {
                                    log.debug("Future returned, mtu is {}", mtu)
                                    interfaceMTU = mtu
                                    makeDhcpReply
                                }
                        })
                    } else {
                        return makeDhcpReply
                    }
                }
            } catch {
                case e: StateAccessException => //do nothing; try another subnet
                    log.debug("Exception: {}", e)
            }
        }
        // Couldn't find a static DHCP host assignment for this mac.
        Promise.successful(false)
    }

    private def makeDhcpReply: Future[Boolean] = {
        val chaddr = request.getClientHardwareAddress
        if (null == chaddr) {
            log.warning("handleDhcpRequest dropping bootrequest with null chaddr")
            return Promise.successful(false)
        }
        if (chaddr.length != 6) {
            log.warning("handleDhcpRequest dropping bootrequest with chaddr "
                + "with length {} greater than 6.", chaddr.length)
            return Promise.successful(false)
        }
        log.debug("handleDhcpRequest: on port {} bootrequest with chaddr {} "
            + "and ciaddr {}",
            Array(inPortId, Net.convertByteMacToString(chaddr),
                IPv4.fromIPv4Address(request.getClientIPAddress)))

        // Extract all the options and put them in a map
        val reqOptions = mutable.HashMap[Byte, DHCPOption]()
        val requestedCodes = mutable.Set[Byte]()
        for (opt <- request.getOptions) {
            val code = opt.getCode
            reqOptions.put(code, opt)
            log.debug("handleDhcpRequest found option {}:{}", code,
                DHCPOption.codeToName.get(code))
            code match {
                case v if (v == DHCPOption.Code.DHCP_TYPE.value) =>
                    if (opt.getLength != 1) {
                        log.warning("handleDhcpRequest dropping bootrequest - "
                            + "dhcp msg type option has bad length or data.")
                        return Promise.failed(
                            new Exception("DHCP request with bad dhcp type."))
                    }
                    val msgType = opt.getData()(0)
                    log.debug("handleDhcpRequest dhcp msg type {}:{}",
                        msgType, DHCPOption.msgTypeToName.get(msgType))
                case v if (v == DHCPOption.Code.PRM_REQ_LIST.value) =>
                    if (opt.getLength <= 0) {
                        log.warning("handleDhcpRequest dropping bootrequest - "
                            + "param request list has bad length")
                        return Promise.failed(
                            new Exception("DHCP request with bad param list"))
                    }
                    for (c <- opt.getData) {
                        requestedCodes.add(c)
                        log.debug("handleDhcpRequest client requested option "
                            + "{}:{}", c, DHCPOption.codeToName.get(c))
                    }
                case _ => // Do nothing
            }
        }
        log.debug("What Type is this DHCP packet?")
        var options = mutable.ListBuffer[DHCPOption]()
        reqOptions.get(DHCPOption.Code.DHCP_TYPE.value) match {
            case None =>
                log.warning("handleDhcpRequest dropping bootrequest - no dhcp msg "
                    + "type found.")
                return return Promise.failed(
                    new Exception("DHCP request with missing dhcp type."))
            case Some(typeOpt) =>
                typeOpt.getData()(0) match {
                    case v if (v == DHCPOption.MsgType.DISCOVER.value) =>
                        log.debug("Received a Discover message.")
                        // Reply with a dchp OFFER.
                        options.add(new DHCPOption(
                            DHCPOption.Code.DHCP_TYPE.value,
                            DHCPOption.Code.DHCP_TYPE.length,
                            Array[Byte](DHCPOption.MsgType.OFFER.value)))
                    case v if (v == DHCPOption.MsgType.REQUEST.value) =>
                        log.debug("Received a Request message.")
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
                                log.debug("handleDhcpRequest - no " +
                                    "server id option found.")
                                // TODO(pino): return Promise.successful(false)?
                            case Some(opt) =>
                                // The server id should correspond to this port's address.
                                val theirServId = IPv4.toIPv4Address(opt.getData)
                                if (serverAddr.addressAsInt != theirServId) {
                                    log.warning("handleDhcpRequest dropping dhcp REQUEST - client "
                                        + "chose server {} not us {}",
                                        IPv4.fromIPv4Address(theirServId), serverAddr)
                                }
                        }
                        // The request must contain a requested IP address option.
                        reqOptions.get(DHCPOption.Code.REQUESTED_IP.value) match {
                            case None =>
                                log.debug("handleDhcpRequest - no "
                                    + "requested ip option found.")
                                //return Promise.failed(new Exception(
                                //    "DHCP message with no requested-IP option."))
                            case Some(opt) =>
                                // The requested ip must correspond to the yiaddr in our offer.
                                val reqIp = IPv4.toIPv4Address(opt.getData)
                                // TODO(pino): must keep state and remember the offered ip based
                                // on the chaddr or the client id option.
                                if (yiaddr.addressAsInt != reqIp) {
                                    log.warning("handleDhcpRequest dropping dhcp REQUEST " +
                                        "- the requested ip {} is not the offered " +
                                        "yiaddr {}", reqIp, yiaddr)
                                    // TODO(pino): send a dhcp NAK reply.
                                    return Promise.successful(true)
                                }
                        }
                    case msgType =>
                        log.warning("handleDhcpRequest dropping bootrequest - we don't "
                            + "handle msg type {}:{}", msgType,
                            DHCPOption.msgTypeToName.get(msgType));
                        Promise.failed(
                            new Exception("DHCP request with missing dhcp type."))
                }
        }

        val reply = new DHCP
        reply.setOpCode(DHCP.OPCODE_REPLY)
        reply.setTransactionId(request.getTransactionId)
        reply.setHardwareAddressLength(6)
        reply.setHardwareType(ARP.HW_TYPE_ETHERNET.toByte)
        reply.setClientHardwareAddress(sourceMac)
        reply.setServerIPAddress(serverAddr.getAddress)
        reply.setYourIPAddress(yiaddr.getAddress)
        // TODO(pino): do we need to include the DNS option?
        options.add(new DHCPOption(DHCPOption.Code.MASK.value,
            DHCPOption.Code.MASK.length,
            IPv4.toIPv4AddressBytes(~0 << (32 - yiaddr.getMaskLength))))
        // Generate the broadcast address... this is nwAddr with 1's in the
        // last 32-nwAddrLength bits.
        val mask = ~0 >>> yiaddr.getMaskLength
        val bcast = mask | yiaddr.getAddress
        log.debug("handleDhcpRequest setting bcast addr option to {}",
            IPv4.fromIPv4Address(bcast))
        options.add(new DHCPOption(
            DHCPOption.Code.BCAST_ADDR.value,
            DHCPOption.Code.BCAST_ADDR.length,
            IPv4.toIPv4AddressBytes(bcast)))
        options.add(new DHCPOption(
            DHCPOption.Code.IP_LEASE_TIME.value,
            DHCPOption.Code.IP_LEASE_TIME.length,
            // This is in seconds.  One day is more than enough.
            IPv4.toIPv4AddressBytes((1 day).toSeconds.toInt)))
        options.add(new DHCPOption(DHCPOption.Code.INTERFACE_MTU.value,
            DHCPOption.Code.INTERFACE_MTU.length,
            Array[Byte]((interfaceMTU/256).toByte, (interfaceMTU%256).toByte)))
        if (routerAddr != null) {
            options.add(new DHCPOption(
                DHCPOption.Code.ROUTER.value,
                DHCPOption.Code.ROUTER.length,
                IPv4.toIPv4AddressBytes(routerAddr.getAddress)))
        }
        // in MidoNet the DHCP server is the same as the router
        options.add(new DHCPOption(
            DHCPOption.Code.SERVER_ID.value,
            DHCPOption.Code.SERVER_ID.length,
            IPv4.toIPv4AddressBytes(serverAddr.getAddress)))

        if (dnsServerAddrs != null && dnsServerAddrs.length > 0) {
            val bytes = mutable.ListBuffer[Byte]()
            for (dnsIp <- dnsServerAddrs) {
                val dnsServerBytes = IPv4.toIPv4AddressBytes(dnsIp.addressAsInt)
                bytes.appendAll(dnsServerBytes.toList)
            }
            options.add(new DHCPOption(DHCPOption.Code.DNS.value,
                            bytes.length.toByte,
                            bytes.toArray))
        }
        // If there are classless static routes, add the option.
        if (null != opt121Routes && opt121Routes.length > 0) {
            val bytes = mutable.ListBuffer[Byte]()
            for (rt <- opt121Routes) {
                log.debug("Found classless route {}", rt)
                // First append the destination subnet's maskLength
                val maskLen = rt.getRtDstSubnet.getMaskLength.toByte
                bytes.append(maskLen)
                // Now append the significant octets of the subnet.
                val dstBytes = IPv4.toIPv4AddressBytes(
                    rt.getRtDstSubnet.addressAsInt)
                if (maskLen > 0)
                    bytes.append(dstBytes(0))
                if (maskLen > 8)
                    bytes.append(dstBytes(1))
                if (maskLen > 16)
                    bytes.append(dstBytes(2))
                if (maskLen > 24)
                    bytes.append(dstBytes(3))
                // Now append the 4 octets of the gateway.
                val gwBytes = IPv4.toIPv4AddressBytes(
                    rt.getGateway.addressAsInt)
                bytes.appendAll(gwBytes.toList)
            }
            log.debug("Adding Option 121 (classless static routes) with " +
                "{} routes", opt121Routes.length)
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
        ip.setSourceAddress(serverAddr.getAddress)
        ip.setDestinationAddress("255.255.255.255")
        ip.setProtocol(UDP.PROTOCOL_NUMBER)
        ip.setPayload(udp)

        val eth = new Ethernet
        eth.setEtherType(IPv4.ETHERTYPE)
        eth.setPayload(ip)

        eth.setSourceMACAddress(serverMac)
        eth.setDestinationMACAddress(sourceMac)

        log.debug("handleDhcpRequest: sending DHCP reply {} to port {}", eth,
            inPortId)

        // Emit our DHCP reply packet
        DeduplicationActor.getRef(actorSystem) ! EmitGeneratedPacket(
            inPortId, eth, cookie)
        // Tell the FlowController not to track the packet anymore.
        Promise.successful(true)
    }

    private def calculateInterfaceMTU : Future[Short] = {
        var minMtu : Short = 0
        var overhead : Short = 0
        var intfMtu : Short = 0
        ask(datapathController, LocalTunnelInterfaceInfo())(
            Platform.currentTime + (3 seconds).toMillis)
        .mapTo[mutable.MultiMap[InterfaceDescription, TunnelZone.Type]]
        .map {
            interfaceTunnelList :
                mutable.MultiMap[InterfaceDescription, TunnelZone.Type] => {
                for ((interfaceDesc, tunnelTypeList) <- interfaceTunnelList) {
                    for (tunnelType <- tunnelTypeList) {
                        tunnelType match {
                            case TunnelZone.Type.Gre =>
                                overhead = (new GreTunnelZone).getTunnelOverhead()
                            case TunnelZone.Type.Capwap =>
                                overhead = (new CapwapTunnelZone).getTunnelOverhead()
                            case TunnelZone.Type.Ipsec =>
                                overhead = (new IpsecTunnelZone).getTunnelOverhead()
                        }
                        intfMtu = interfaceDesc.getMtu().toShort
                        val tunnelMtu = (intfMtu - overhead).toShort
                        if (minMtu == 0) minMtu = tunnelMtu
                        else if (minMtu > tunnelMtu) {
                            minMtu = tunnelMtu
                        }
                        log.info("Interface {}, tunnel type {}, minMtu is {}", interfaceDesc, tunnelType, minMtu)
                    }
                }
                if (minMtu == 0) 1500
                else minMtu
            }
         }
    }
}
