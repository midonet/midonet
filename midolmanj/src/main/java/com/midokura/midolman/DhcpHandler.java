/*
 * Copyright 2012 Midokura Europe SARL
 */

package com.midokura.midolman;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.packets.ARP;
import com.midokura.midolman.packets.DHCP;
import com.midokura.midolman.packets.DHCPOption;
import com.midokura.midolman.packets.Ethernet;
import com.midokura.midolman.packets.IPv4;
import com.midokura.midolman.packets.MAC;
import com.midokura.midolman.packets.UDP;
import com.midokura.midolman.util.Net;

public class DhcpHandler {

    private static final Logger log = LoggerFactory
            .getLogger(DhcpHandler.class);

    public DhcpHandler() {
    }

    public void handleDhcpRequest(L3DevicePort devPortIn, DHCP request,
                                   MAC sourceMac) {
        /* TODO: Break this up, it's too long. */
        byte[] chaddr = request.getClientHardwareAddress();
        if (null == chaddr) {
            log.warn("handleDhcpRequest dropping bootrequest with null chaddr");
            return;
        }
        if (chaddr.length != 6) {
            log.warn("handleDhcpRequest dropping bootrequest with chaddr "
                    + "with length {} greater than 6.", chaddr.length);
            return;
        }
        log.debug("handleDhcpRequest: on port {} bootrequest with chaddr {} "
                + "and ciaddr {}",
                new Object[] { devPortIn, Net.convertByteMacToString(chaddr),
                        IPv4.fromIPv4Address(request.getClientIPAddress()) });

        // Extract all the options and put them in a map
        Map<Byte, DHCPOption> reqOptions = new HashMap<Byte, DHCPOption>();
        Set<Byte> requestedCodes = new HashSet<Byte>();
        for (DHCPOption opt : request.getOptions()) {
            byte code = opt.getCode();
            reqOptions.put(code, opt);
            log.debug("handleDhcpRequest found option {}:{}", code,
                    DHCPOption.codeToName.get(code));
            if (code == DHCPOption.Code.DHCP_TYPE.value()) {
                if (opt.getLength() != 1) {
                    log.warn("handleDhcpRequest dropping bootrequest - "
                            + "dhcp msg type option has bad length or data.");
                    return;
                }
                log.debug("handleDhcpRequest dhcp msg type {}:{}",
                        opt.getData()[0],
                        DHCPOption.msgTypeToName.get(opt.getData()[0]));
            }
            if (code == DHCPOption.Code.PRM_REQ_LIST.value()) {
                if (opt.getLength() <= 0) {
                    log.warn("handleDhcpRequest dropping bootrequest - "
                            + "param request list has bad length");
                    return;
                }
                for (int i = 0; i < opt.getLength(); i++) {
                    byte c = opt.getData()[i];
                    requestedCodes.add(c);
                    log.debug("handleDhcpRequest client requested option "
                            + "{}:{}", c, DHCPOption.codeToName.get(c));
                }
            }
        }
        DHCPOption typeOpt = reqOptions.get(DHCPOption.Code.DHCP_TYPE.value());
        if (null == typeOpt) {
            log.warn("handleDhcpRequest dropping bootrequest - no dhcp msg "
                    + "type found.");
            return;
        }
        byte msgType = typeOpt.getData()[0];
        boolean drop = true;
        List<DHCPOption> options = new ArrayList<DHCPOption>();
        DHCPOption opt;
        if (DHCPOption.MsgType.DISCOVER.value() == msgType) {
            drop = false;
            // Reply with a dchp OFFER.
            opt = new DHCPOption(DHCPOption.Code.DHCP_TYPE.value(),
                    DHCPOption.Code.DHCP_TYPE.length(),
                    new byte[] { DHCPOption.MsgType.OFFER.value() });
            options.add(opt);

        } else if (DHCPOption.MsgType.REQUEST.value() == msgType) {
            drop = false;
            // Reply with a dchp ACK.
            opt = new DHCPOption(DHCPOption.Code.DHCP_TYPE.value(),
                    DHCPOption.Code.DHCP_TYPE.length(),
                    new byte[] { DHCPOption.MsgType.ACK.value() });
            options.add(opt);
            // http://tools.ietf.org/html/rfc2131 Section 3.1, Step 3:
            // "The client broadcasts a DHCPREQUEST message that MUST include
            // the 'server identifier' option to indicate which server is has
            // selected."
            // TODO(pino): figure out why Linux doesn't send us the server id
            // and try re-enabling this code.
            opt = reqOptions.get(DHCPOption.Code.SERVER_ID
                    .value());
            if (null == opt) {
                log.warn("handleDhcpRequest dropping dhcp REQUEST - no " +
                        "server id option found.");
                // TODO(pino): re-enable this.
                //return;
            } else {
                // The server id should correspond to this port's address.
                int ourServId = devPortIn.getVirtualConfig().portAddr;
                int theirServId = IPv4.toIPv4Address(opt.getData());
                if (ourServId != theirServId) {
                    log.warn("handleDhcpRequest dropping dhcp REQUEST - client "
                            + "chose server {} not us {}",
                            IPv4.fromIPv4Address(theirServId),
                            IPv4.fromIPv4Address(ourServId));
                }
            }
            // The request must contain a requested IP address option.
            opt = reqOptions.get(DHCPOption.Code.REQUESTED_IP.value());
            if (null == opt) {
                log.warn("handleDhcpRequest dropping dhcp REQUEST - no "
                        + "requested ip option found.");
                return;
            }
            // The requested ip must correspond to the yiaddr in our offer.
            int reqIp = IPv4.toIPv4Address(opt.getData());
            int offeredIp = devPortIn.getVirtualConfig().localNwAddr;
            // TODO(pino): must keep state and remember the offered ip based
            // on the chaddr or the client id option.
            if (reqIp != offeredIp) {
                log.warn("handleDhcpRequest dropping dhcp REQUEST - the " +
                        "requested ip {} is not the offered yiaddr {}",
                        IPv4.fromIPv4Address(reqIp), IPv4.fromIPv4Address(offeredIp));
                // TODO(pino): send a dhcp NAK reply.
                return;
            }
        }
        if (drop) {
            log.warn("handleDhcpRequest dropping bootrequest - we don't "
                    + "handle msg type {}:{}", msgType,
                    DHCPOption.msgTypeToName.get(msgType));
            return;
        }
        DHCP reply = new DHCP();
        reply.setOpCode(DHCP.OPCODE_REPLY);
        reply.setTransactionId(request.getTransactionId());
        reply.setHardwareAddressLength((byte) 6);
        reply.setHardwareType((byte) ARP.HW_TYPE_ETHERNET);
        reply.setClientHardwareAddress(sourceMac);
        reply.setServerIPAddress(devPortIn.getVirtualConfig().portAddr);
        // TODO(pino): use explicitly assigned address not localNwAddr!!
        reply.setYourIPAddress(devPortIn.getVirtualConfig().localNwAddr);
        // TODO(pino): do we need to include the DNS option?
        opt = new DHCPOption(DHCPOption.Code.MASK.value(),
                DHCPOption.Code.MASK.length(),
                IPv4.toIPv4AddressBytes(~0 << (32 - devPortIn
                        .getVirtualConfig().nwLength)));
        options.add(opt);
        // Generate the broadcast address... this is nwAddr with 1's in the
        // last 32-nwAddrLength bits.
        int mask = ~0 >>> devPortIn.getVirtualConfig().nwLength;
        int bcast = mask | devPortIn.getVirtualConfig().nwAddr;
        log.debug("handleDhcpRequest setting bcast addr option to {}",
                IPv4.fromIPv4Address(bcast));
        opt = new DHCPOption(
                DHCPOption.Code.BCAST_ADDR.value(),
                DHCPOption.Code.BCAST_ADDR.length(),
                IPv4.toIPv4AddressBytes(bcast));
        options.add(opt);
        opt = new DHCPOption(
                DHCPOption.Code.IP_LEASE_TIME.value(),
                DHCPOption.Code.IP_LEASE_TIME.length(),
                // This is in seconds... is 1 day enough?
                IPv4.toIPv4AddressBytes(86400));
        options.add(opt);
        opt = new DHCPOption(
                DHCPOption.Code.ROUTER.value(),
                DHCPOption.Code.ROUTER.length(),
                IPv4.toIPv4AddressBytes(devPortIn.getVirtualConfig().portAddr));
        options.add(opt);
        // in MidoNet the DHCP server is the same as the router
        opt = new DHCPOption(
                DHCPOption.Code.SERVER_ID.value(),
                DHCPOption.Code.SERVER_ID.length(),
                IPv4.toIPv4AddressBytes(devPortIn.getVirtualConfig().portAddr));
        options.add(opt);
        // And finally add the END option.
        opt = new DHCPOption(DHCPOption.Code.END.value(),
                DHCPOption.Code.END.length(), null);
        options.add(opt);
        reply.setOptions(options);

        UDP udp = new UDP();
        udp.setSourcePort((short) 67);
        udp.setDestinationPort((short) 68);
        udp.setPayload(reply);

        IPv4 ip = new IPv4();
        ip.setSourceAddress(devPortIn.getVirtualConfig().portAddr);
        ip.setDestinationAddress("255.255.255.255");
        ip.setProtocol(UDP.PROTOCOL_NUMBER);
        ip.setPayload(udp);

        Ethernet eth = new Ethernet();
        eth.setEtherType(IPv4.ETHERTYPE);
        eth.setPayload(ip);

        eth.setSourceMACAddress(devPortIn.getMacAddr());
        eth.setDestinationMACAddress(sourceMac);

        log.debug("handleDhcpRequest: sending DHCP reply {} to port {}", eth,
                devPortIn);
        devPortIn.send(eth.serialize());
        //sendUnbufferedPacketFromPort(eth, devPortIn.getNum());
    }

}
