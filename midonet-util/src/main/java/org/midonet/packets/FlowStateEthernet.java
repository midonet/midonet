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

package org.midonet.packets;

import java.nio.ByteBuffer;

/**
 * FlowStateEthernet is an ethernet frame contains a flow state UDP packet,
 * which length is flexible.
 *
 *  0               8               16              24             32
 *  Ethernet Frame Header
 *  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *  |                  Destination MAC Address...                   |
 *  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *  |         ...(cont.)            |      Source MAC Address...    |
 *  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *  |                          ...(cont.)                           |
 *  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *  |       EtherType/Length        |
 *  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *  IP header
 *  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *  |Version|  IDL  |    DSCP   |ECN|        Total Length           |
 *  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *  |        Identification         |Flags|      Fragment Offset    |
 *  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *  |      TTL      |    Protocol   |        Header Checksum        |
 *  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *  |                       Source IP address                       |
 *  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *  |                     Destination IP address                    |
 *  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *  UDP header
 *  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *  |           Source Port         |        Destination Port       |
 *  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *  |             Length            |             Checksum          |
 *  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 */
public final class FlowStateEthernet extends Ethernet {
    public final static int MTU = 1500;
    // Ethernet(IEE 802.3): http://standards.ieee.org/about/get/802/802.3.html
    // IP: https://tools.ietf.org/html/rfc791
    // UDP: http://tools.ietf.org/html/rfc768
    // VXLAN: http://tools.ietf.org/html/rfc7348#section-5
    // 20(IP) + 8(UDP) + 8(VXLAN)
    public final static int VXLAN_HEADER_LEN = 8;
    public final static int VXLAN_ENCAPUSULATION_OVERHEAD =
            IPv4.MIN_HEADER_LEN + UDP.HEADER_LEN + VXLAN_HEADER_LEN;
    public final static int FLOW_STATE_ETHERNET_OVERHEAD =
            Ethernet.MIN_HEADER_LEN + IPv4.MIN_HEADER_LEN + UDP.HEADER_LEN;
    public final static int OVERHEAD =
            VXLAN_ENCAPUSULATION_OVERHEAD + FLOW_STATE_ETHERNET_OVERHEAD;
    public final static int FLOW_STATE_MAX_PAYLOAD_LENGTH = MTU - OVERHEAD;
    public final static int FLOW_STATE_IP_HEADER_OFFSET = 14;
    public final static int FLOW_STATE_UDP_HEADER_OFFSET = 34;
    public final static int FLOW_STATE_IP_CHECKSUM_OFFSET =
            FLOW_STATE_IP_HEADER_OFFSET + 10;
    public final static int FLOW_STATE_IP_LENGTH_OFFSET =
            FLOW_STATE_IP_HEADER_OFFSET + 2;
    public final static int FLOW_STATE_UDP_LENGTH_OFFSET =
            FLOW_STATE_UDP_HEADER_OFFSET + 4;
    public final static int FLOW_STATE_UDP_CHECKSUM_OFFSET =
            FLOW_STATE_UDP_HEADER_OFFSET + 6;

    public final static String FLOW_STATE_ETHERNET_SOURCE_MAC =
            "AC:CA:BA:00:15:01";
    public final static String FLOW_STATE_ETHERNET_DESTINATION_MAC =
            "AC:CA:BA:00:15:02";
    public final static short FLOW_STATE_ETHERNET_TYPE = (short) 0x800;
    public final static short FLOW_STATE_IP_IDENTIFICATION = (short) 0x01;
    public final static byte FLOW_STATE_IP_VERSION = (byte) 0x04;
    public final static byte FLOW_STATE_IP_HEADER_LENGTH = (byte) 0x05;
    public final static byte FLOW_STATE_IP_DIFF_SERV = (byte) 0x00;
    public final static byte FLOW_STATE_IP_FLAGS = (byte) 0x02;
    public final static short FLOW_STATE_IP_FRAGMENT_OFFSET = (short) 0;
    // TODO(tfukushima): Get the accurate TTL and use it if possible.
    public final static byte FLOW_STATE_IP_TTL = (byte) 64;
    public final static byte FLOW_STATE_IP_PROTOCOL = (byte) 0x11;
    public final static IPv4Addr FLOW_STATE_IP_SRC_ADDRESS =
            IPv4Addr.fromString("169.254.15.1");
    public final static IPv4Addr FLOW_STATE_IP_DST_ADDRESS =
            IPv4Addr.fromString("169.254.15.2");
    public final static int FLOW_STATE_UDP_PORT = 2925;

    private byte[] cachedFlowStateEthernetHeader;
    private final ElasticData data;

    public FlowStateEthernet(byte[] buffer) {
        super();
        data = new ElasticData(buffer);
        cachedFlowStateEthernetHeader = buildHeaders(data);
    }

    @Override
    public int getPayloadLength() {
        return data.limit();
    }

    @Override
    public int serialize(ByteBuffer bb) {
        int start = bb.position();
        bb.put(cachedFlowStateEthernetHeader);
        int elasticDataLength = getPayloadLength();
        putElasticData(bb, start, elasticDataLength);
        return FLOW_STATE_ETHERNET_OVERHEAD + elasticDataLength;
    }

    @Override
    public byte[] serialize() {
        ((IPv4)payload).setChecksum((short)0);
        ((IPv4)payload).setTotalLength(0);
        return super.serialize();
    }

    public void limit(int limit) {
        data.limit(limit);
    }

    private void putElasticData(ByteBuffer bb, int start, int elasticDataLength) {
        data.serialize(bb);
        short ipLength = (short) (
                IPv4.MIN_HEADER_LEN + UDP.HEADER_LEN + elasticDataLength);
        bb.putShort(start + FLOW_STATE_IP_LENGTH_OFFSET, ipLength);
        short ipChecksum = IPv4.computeChecksum(
                bb.array(), start + FLOW_STATE_IP_HEADER_OFFSET,
                IPv4.MIN_HEADER_LEN, start + FLOW_STATE_IP_CHECKSUM_OFFSET);
        bb.putShort(start + FLOW_STATE_IP_CHECKSUM_OFFSET, ipChecksum);
        short udpLength = (short) (UDP.HEADER_LEN + elasticDataLength);
        bb.putShort(start+ FLOW_STATE_UDP_LENGTH_OFFSET, udpLength);
    }

    private byte[] buildHeaders(ElasticData data) {
        setSourceMACAddress(MAC.fromString(FLOW_STATE_ETHERNET_SOURCE_MAC));
        setDestinationMACAddress(
            MAC.fromString(FLOW_STATE_ETHERNET_DESTINATION_MAC));
        setEtherType(FLOW_STATE_ETHERNET_TYPE);

        IPv4 ipv4 = new IPv4();
        ipv4.setVersion(FlowStateEthernet.FLOW_STATE_IP_VERSION);
        ipv4.setHeaderLength(FlowStateEthernet.FLOW_STATE_IP_HEADER_LENGTH);
        ipv4.setDiffServ(FlowStateEthernet.FLOW_STATE_IP_DIFF_SERV);
        ipv4.setTotalLength(IPv4.MIN_HEADER_LEN);
        ipv4.setIdentification(FlowStateEthernet.FLOW_STATE_IP_IDENTIFICATION);
        ipv4.setFlags(FlowStateEthernet.FLOW_STATE_IP_FLAGS);
        ipv4.setFragmentOffset(FlowStateEthernet.FLOW_STATE_IP_FRAGMENT_OFFSET);
        ipv4.setTtl(FlowStateEthernet.FLOW_STATE_IP_TTL);
        ipv4.setProtocol(FlowStateEthernet.FLOW_STATE_IP_PROTOCOL);
        ipv4.setSourceAddress(FlowStateEthernet.FLOW_STATE_IP_SRC_ADDRESS);
        ipv4.setDestinationAddress(FlowStateEthernet.FLOW_STATE_IP_DST_ADDRESS);
        ipv4.setParent(this);
        setPayload(ipv4);

        UDP udp = new UDP();
        udp.setSourcePort((short) FLOW_STATE_UDP_PORT);
        udp.setDestinationPort((short) FLOW_STATE_UDP_PORT);
        udp.setParent(ipv4);
        ipv4.setPayload(udp);

        byte[] serialized = serialize();
        // We don't specify the UDP checksum
        ByteBuffer.wrap(serialized).putShort(FLOW_STATE_UDP_CHECKSUM_OFFSET,
                                             (short) 0);

        udp.setPayload(data);
        data.setParent(udp);
        return serialized;
    }
}
