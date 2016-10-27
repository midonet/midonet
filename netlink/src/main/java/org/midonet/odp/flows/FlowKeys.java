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
package org.midonet.odp.flows;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import org.midonet.netlink.AttributeHandler;
import org.midonet.netlink.NetlinkMessage;
import org.midonet.netlink.Writer;
import org.midonet.odp.OpenVSwitch.FlowKey.Attr;
import org.midonet.packets.ARP;
import org.midonet.packets.Ethernet;
import org.midonet.packets.ICMP;
import org.midonet.packets.IPFragmentType;
import org.midonet.packets.IPacket;
import org.midonet.packets.IPv4;
import org.midonet.packets.IPv4Addr;
import org.midonet.packets.IPv6;
import org.midonet.packets.IPv6Addr;
import org.midonet.packets.MAC;
import org.midonet.packets.TCP;
import org.midonet.packets.UDP;
import org.midonet.util.collection.WeakObjectPool;

/**
 * Builder class to allow easier building of FlowKey instances.
 */
public class FlowKeys {
    private static WeakObjectPool<FlowKey> FLOW_KEYS_POOL =
        new WeakObjectPool<>();

    public static <T extends FlowKey> T intern(T flowKey) {
        if (flowKey instanceof CachedFlowKey)
            return sharedReferenceOf(flowKey);
        else
            return flowKey;
    }

    public static <T extends FlowKey> T sharedReferenceOf(T flowKey) {
        @SuppressWarnings("unchecked")
        T shared = (T) FLOW_KEYS_POOL.sharedRef(flowKey);
        return shared;
    }

    public static FlowKeyInPort inPort(int portNumber) {
        return intern(new FlowKeyInPort(portNumber));
    }

    public static FlowKeyEthernet ethernet(byte[] src, byte[] dst) {
        return intern(new FlowKeyEthernet(src, dst));
    }

    public static FlowKeyEthernet ethernet(MAC src, MAC dst) {
        return ethernet(src.getAddress(), dst.getAddress());
    }

    public static FlowKeyEtherType etherType(short value) {
        return intern(new FlowKeyEtherType(value));
    }

    public static FlowKeyEtherType etherType(FlowKeyEtherType.Type type) {
        return etherType((short)type.value);
    }

    public static FlowKeyIPv4 ipv4(IPv4Addr src, IPv4Addr dst,
                                   IpProtocol protocol) {
        return ipv4(src, dst, protocol.value, (byte)0, (byte)0, IPFragmentType.None);
    }

    /**
     * Will default TOS to 0 if null. All other fields considered mandatory.
     */
    public static FlowKeyIPv4 ipv4(IPv4Addr src, IPv4Addr dst, byte protocol,
                                   Byte typeOfService, byte ttl,
                                   IPFragmentType fragmentType) {
        return intern(new FlowKeyIPv4(src.toInt(), dst.toInt(), protocol,
                                      typeOfService == null ? 0 : typeOfService,
                                      ttl, fragmentType.value));
    }

    public static FlowKeyIPv4 ipv4(int src, int dst, byte protocol,
                                   byte typeOfService, byte ttl,
                                   byte fragmentType) {
        return intern(new FlowKeyIPv4(src, dst, protocol, typeOfService,
                                      ttl, fragmentType));
    }

    public static FlowKeyIPv6 ipv6(IPv6Addr src, IPv6Addr dst,
                                   IpProtocol protocol) {
        return ipv6(src, dst, protocol.value, (byte)0, IPFragmentType.None);
    }

    public static FlowKeyIPv6 ipv6(IPv6Addr src, IPv6Addr dst,
                                   byte protocol) {
        return ipv6(src, dst, protocol, (byte)0, IPFragmentType.None);
    }

    public static FlowKeyIPv6 ipv6(IPv6Addr src, IPv6Addr dst, byte protocol,
                                   byte hlimit, IPFragmentType fragmentType) {
        return intern(new FlowKeyIPv6(src, dst, protocol,
                                      hlimit, fragmentType.value));
    }

    public static FlowKeyICMP icmp(byte type, byte code) {
        return intern(new FlowKeyICMP(type, code));
    }

    public static FlowKeyICMPEcho icmpEcho(byte type, byte code, int id) {
        return intern(new FlowKeyICMPEcho(type, code, id));
    }

    public static FlowKeyICMPError icmpError(byte type, byte code, byte[] data) {
        return intern(new FlowKeyICMPError(type, code, data));
    }

    public static FlowKeyICMPv6 icmpv6(int type, int code) {
        return intern(new FlowKeyICMPv6((byte) type, (byte) code));
    }

    public static FlowKeyUDP udp(int src, int dst) {
        return intern(new FlowKeyUDP(src, dst));
    }

    public static FlowKeyARP arp(byte[] sourceAddress, byte[] targetAddress,
                                 short opcode, int sourceIp, int targetIp) {
        return intern(new FlowKeyARP(sourceAddress, targetAddress, opcode,
                                     sourceIp, targetIp));
    }

    public static FlowKeyTCP tcp(int src, int dst) {
        return intern(new FlowKeyTCP(src, dst));
    }

    public static FlowKeyTCPFlags tcpFlags(short flags) {
        return intern(new FlowKeyTCPFlags(flags));
    }

    public static FlowKeyTCPFlags tcpFlags(List<TCP.Flag> flags) {
        return intern(new FlowKeyTCPFlags(flags));
    }

    public static FlowKeyND neighborDiscovery(int[] target) {
        return intern(new FlowKeyND(target));
    }

    public static FlowKeyPriority priority(int priority) {
        return intern(new FlowKeyPriority(priority));
    }

    public static FlowKeyVLAN vlan(short vlan) {
        return intern(new FlowKeyVLAN(vlan));
    }

    public static FlowKeyEncap encap(List<FlowKey> keys) {
        return intern(new FlowKeyEncap(keys));
    }

    public static FlowKeyTunnel tunnel(long id, int srcIpv4, int dstIpv4, byte tos) {
        return intern(new FlowKeyTunnel(id, srcIpv4, dstIpv4, tos));
    }

    public static FlowKeyTunnel tunnel(long id, int srcIpv4, int dstIpv4, byte tos, byte ttl) {
        return intern(new FlowKeyTunnel(id, srcIpv4, dstIpv4, tos, ttl));
    }

    public static FlowKey newBlankInstance(short type) {
        switch (NetlinkMessage.unnest(type)) {

            case Attr.Encap:
                return new FlowKeyEncap();

            case Attr.Priority:
                return new FlowKeyPriority();

            case Attr.InPort:
                return new FlowKeyInPort();

            case Attr.Ethernet:
                return new FlowKeyEthernet();

            case Attr.VLan:
                return new FlowKeyVLAN();

            case Attr.Ethertype:
                return new FlowKeyEtherType();

            case Attr.IPv4:
                return new FlowKeyIPv4();

            case Attr.IPv6:
                return new FlowKeyIPv6();

            case Attr.TCP:
                return new FlowKeyTCP();

            case Attr.TcpFlags:
                return new FlowKeyTCPFlags();

            case Attr.UDP:
                return new FlowKeyUDP();

            case Attr.ICMP:
                return new FlowKeyICMP();

            case Attr.ICMPv6:
                return new FlowKeyICMPv6();

            case Attr.ARP:
                return new FlowKeyARP();

            case Attr.ND:
                return new FlowKeyND();

            case Attr.Tunnel:
                return new FlowKeyTunnel();

            case Attr.SCTP:
                return new FlowKeySCTP();

            default:
                return null;
        }
    }

    /** stateless serialiser and deserialiser of ovs FlowKey classes. Used
     *  as a typeclass with NetlinkMessage.writeAttr() and writeAttrSet()
     *  for assembling ovs requests. */
    public static final Writer<FlowKey> writer = new Writer<FlowKey>() {
        public short attrIdOf(FlowKey value) {
            return value.attrId();
        }
        public int serializeInto(ByteBuffer receiver, FlowKey value) {
            return value.serializeInto(receiver);
        }
    };

    public static ArrayList<FlowKey> randomKeys() {
        ArrayList<FlowKey> keys = new ArrayList<>();
        while (ThreadLocalRandom.current().nextInt(100) >= 30 && keys.size() <= 10) {
            keys.add(randomKey());
        }
        return keys;
    }

    public static FlowKey randomKey() {
        FlowKey k = null;
        while (k == null) {
            k = FlowKeys.newBlankInstance((short)(1 + ThreadLocalRandom.current().nextInt(18)));
        }
        if (k instanceof Randomize) {
            ((Randomize)k).randomize();
        } else {
            byte[] bytes = new byte[1024];
            ThreadLocalRandom.current().nextBytes(bytes);
            k.deserializeFrom(ByteBuffer.wrap(bytes));
        }
        return k;
    }

    public static ArrayList<FlowKey> fromEthernetPacket(Ethernet ethPkt) {
        ArrayList<FlowKey> keys = new ArrayList<>();
        keys.add(
                ethernet(
                    ethPkt.getSourceMACAddress().getAddress(),
                    ethPkt.getDestinationMACAddress().getAddress()));
        keys.add(etherType(ethPkt.getEtherType()));

        ArrayList<FlowKey> payloadKeys = new ArrayList<>();

        switch (ethPkt.getEtherType()) {
            case ARP.ETHERTYPE:
                if (ethPkt.getPayload() instanceof ARP) {
                    ARP arpPkt = ARP.class.cast(ethPkt.getPayload());
                    payloadKeys.add(
                        arp(arpPkt.getSenderHardwareAddress().getAddress(),
                            arpPkt.getTargetHardwareAddress().getAddress(),
                            arpPkt.getOpCode(),
                            IPv4Addr.bytesToInt(
                                arpPkt.getSenderProtocolAddress()),
                            IPv4Addr.bytesToInt(
                                arpPkt.getTargetProtocolAddress())));
                }
                break;
            case IPv4.ETHERTYPE:
                if (ethPkt.getPayload() instanceof IPv4) {
                    IPv4 ipPkt = IPv4.class.cast(ethPkt.getPayload());
                    parseFlowKeysFromIPv4(ipPkt, payloadKeys);
                }
                break;
            case IPv6.ETHERTYPE:
                if (ethPkt.getPayload() instanceof IPv6) {
                    IPv6 v6Pkt = IPv6.class.cast(ethPkt.getPayload());
                    parseFlowKeysFromIPv6(v6Pkt, keys);
                }
                break;
        }

        List<Short> vlans = ethPkt.getVlanIDs();
        if (vlans.size() == 1 && ethPkt.getVlanIDs().get(0) == 0) {
            keys.add(vlan((short) 0));
            keys.addAll(payloadKeys);
        } else if (vlans.size() > 0) {
            // process VLANS
            Iterator<Short> it = ethPkt.getVlanIDs().iterator();
            while (it.hasNext()) {
                short vlanID = it.next();
                keys.add(
                    etherType(it.hasNext() ? Ethernet.PROVIDER_BRIDGING_TAG :
                              Ethernet.VLAN_TAGGED_FRAME));
                keys.add(vlan(vlanID));
            }
            keys.add(encap(payloadKeys));
        } else {
            keys.addAll(payloadKeys);
        }

        addUserspaceKeys(ethPkt, keys);
        return keys;
    }

    public static boolean needsUserspaceKeyUpdate(List<FlowKey> keys) {
        for (int i = 0; i < keys.size(); ++i) {
            if (keys.get(i) instanceof FlowKeyICMP) {
                return true;
            } else if (keys.get(i) instanceof FlowKeyEncap) {
                return true;
            }
        }
        return false;
    }

    public static void addUserspaceKeys(Ethernet ethPkt, List<FlowKey> keys) {
        FlowKey icmpUserSpace = null;
        if (ethPkt.getPayload() != null &&
            ethPkt.getPayload().getPayload() instanceof ICMP) {
            icmpUserSpace = makeIcmpFlowKey((ICMP) ethPkt.getPayload().getPayload());
        }

        for (int i = 0; i < keys.size(); ++i) {
            if (keys.get(i) instanceof FlowKeyICMP) {
                if (icmpUserSpace != null) {
                    keys.set(i, icmpUserSpace);
                }
            }
            if (keys.get(i) instanceof FlowKeyEncap) {
                FlowKeyEncap encap = (FlowKeyEncap) keys.get(i);
                addUserspaceKeys(ethPkt, encap.keys);
            }
        }
    }

    public static void buildFrom(ByteBuffer buf, final ArrayList<FlowKey> flowKeys) {
        NetlinkMessage.scanAttributes(buf, new AttributeHandler() {
            @Override
            public void use(ByteBuffer buffer, short id) {
                FlowKey key = FlowKeys.newBlankInstance(id);
                if (key == null)
                    return;
                key.deserializeFrom(buffer);
                flowKeys.add(key);
            }
        });
    }

    private static void parseFlowKeysFromIPv4(IPv4 pkt, List<FlowKey> keys) {
        IPFragmentType fragmentType =
            IPFragmentType.fromIPv4Flags(pkt.getFlags(),
                                         pkt.getFragmentOffset());

        byte protocol = pkt.getProtocol();
        IPacket payload = pkt.getPayload();

        keys.add(
            ipv4(pkt.getSourceIPAddress(),
                 pkt.getDestinationIPAddress(),
                 protocol,
                 (byte) 0, /* type of service */
                 pkt.getTtl(),
                 fragmentType)
        );

        switch (protocol) {
            case TCP.PROTOCOL_NUMBER:
                if (payload instanceof TCP) {
                    TCP tcpPkt = TCP.class.cast(payload);
                    keys.add(tcp(tcpPkt.getSourcePort(),
                                 tcpPkt.getDestinationPort()));

                    // add the keys for the TCP flags
                    keys.add(tcpFlags(tcpPkt.getFlags()));
                }
                break;
            case UDP.PROTOCOL_NUMBER:
                if (payload instanceof UDP) {
                    UDP udpPkt = UDP.class.cast(payload);
                    keys.add(udp(udpPkt.getSourcePort(),
                                 udpPkt.getDestinationPort()));
                }
                break;
            case ICMP.PROTOCOL_NUMBER:
                if (payload instanceof ICMP) {
                    ICMP icmpPkt = ICMP.class.cast(payload);
                    FlowKey icmpUserspace = FlowKeys.makeIcmpFlowKey(icmpPkt);
                    if (icmpUserspace == null)
                        keys.add(icmp(icmpPkt.getType(),
                                      icmpPkt.getCode()));
                    else
                        keys.add(icmpUserspace);
                }
                break;
        }
    }

    private static void parseFlowKeysFromIPv6(IPv6 pkt, ArrayList<FlowKey> keys) {
        keys.add(
            ipv6(pkt.getSourceAddress(),
                 pkt.getDestinationAddress(),
                 pkt.getNextHeader()));

        IPacket payload = pkt.getPayload();
        switch (pkt.getNextHeader()) {
            case TCP.PROTOCOL_NUMBER:
                if (payload instanceof TCP) {
                    TCP tcpPkt = TCP.class.cast(payload);
                    keys.add(tcp(tcpPkt.getSourcePort(),
                                  tcpPkt.getDestinationPort()));

                    // add some matches for some important flags
                    keys.add(tcpFlags(tcpPkt.getFlags()));
                }
                break;
            case UDP.PROTOCOL_NUMBER:
                if (payload instanceof UDP) {
                    UDP udpPkt = UDP.class.cast(payload);
                    keys.add(udp(udpPkt.getSourcePort(),
                                 udpPkt.getDestinationPort()));
                }
                break;
        }
    }

    public static FlowKey makeIcmpFlowKey(ICMP icmp) {
        switch (icmp.getType()) {
            case ICMP.TYPE_ECHO_REPLY:
            case ICMP.TYPE_ECHO_REQUEST:
                return icmpEcho(icmp.getType(),
                                icmp.getCode(),
                                icmp.getIdentifier());
            case ICMP.TYPE_PARAMETER_PROBLEM:
            case ICMP.TYPE_REDIRECT:
            case ICMP.TYPE_SOURCE_QUENCH:
            case ICMP.TYPE_TIME_EXCEEDED:
            case ICMP.TYPE_UNREACH:
                return icmpError(icmp.getType(),
                                 icmp.getCode(),
                                 icmp.getData());
            default:
                return null;
        }
    }
}
