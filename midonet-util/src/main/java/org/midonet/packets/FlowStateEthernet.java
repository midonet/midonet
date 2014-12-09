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

import com.google.common.base.Optional;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * FlowStateEthernet is an ethernet frame contains a flow state UDP packet,
 * which length is flexible.
 *
 *  0               8               16              24             32
 *  Ethernet Frame Header
 *  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *  |                       Source MAC Address...                   |
 *  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *  |         ...(cont.)            |   Destination MAC Address...  |
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
public class FlowStateEthernet extends Ethernet {
    public static int MTU = 1500;
    // Ethernet(IEE 802.3): http://standards.ieee.org/about/get/802/802.3.html
    // IP: https://tools.ietf.org/html/rfc791
    // UDP: http://tools.ietf.org/html/rfc768
    // GRE: http://tools.ietf.org/html/rfc2784
    // 20(IP) + 8(GRE+Key)
    public final static int GRE_ENCAPUSULATION_OVERHEAD = 28;
    // 20(IP) + 8(GRE+Key) + 14(Ethernet w/o preamble and CRC) + 20(IP) + 8(UDP)
    public final static int OVERHEAD = 70;
    // 14(Ethernet) + 20(IP) + 8(UDP)
    public final static int FLOW_STATE_ETHERNET_OVERHEAD = 42;
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

    public final static byte[] CACHED_FLOW_STATE_ETHERNET_HEADER = new byte[] {
            // Ethernet header
            // Destination MAC address (6 octets): AC:CA:BA:00:15:01
            // Source MAC address (6 octets): AC:CA:BA:00:15:02
            // EtherType/Length (2 octets): 0x800 (IP)
            (byte) 0xAC, (byte) 0xCA, (byte) 0xBA, (byte) 0x00, (byte) 0x15, (byte) 0x01,
            (byte) 0xAC, (byte) 0xCA, (byte) 0xBA, (byte) 0x00, (byte) 0x15, (byte) 0x02,
            (byte) 0x08, (byte) 0x00,
            // IP header
            // 0               8               16              24             32
            // +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
            // |Version|  IDL  |    DSCP   |ECN|        Total Length           |
            // +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
            // |        Identification         |Flags|      Fragment Offset    |
            // +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
            // |      TTL      |    Protocol   |        Header Checksum        |
            // +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
            // |                       Source IP address                       |
            // +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
            // |                     Destination IP address                    |
            // +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
            (byte) (((0x04) << 4) | 0x05), (byte) 0x00, (byte) 0x00, (byte) 0x1c,
            (byte) 0x00, (byte) 0x01, (byte) 0x5f, (byte) 0xff,
            (byte) 0x01, (byte) 0x11, (byte) 0x00, (byte) 0x00,
            (byte) 0xa9, (byte) 0xfe, (byte) 0x0f, (byte) 0x01, // 169.254.15.1
            (byte) 0xa9, (byte) 0xfe, (byte) 0x0f, (byte) 0x02, // 169.254.15.2
            // UDP header
            // 0               8               16              24             32
            // +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
            // |           Source Port         |        Destination Port       |
            // +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
            // |             Length            |             Checksum          |
            // +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
            (byte) 0x0b, (byte) 0x6d, (byte) 0x0b, (byte) 0x6d, // src/dst port
            (byte) 0x00, (byte) 0x08, (byte) 0x00, (byte) 0x00  // length/chksum
    };

    private static Optional<byte[]> cachedFlowStateEthernetHeader =
            Optional.absent();

    private static void putElasticData(ByteBuffer bb, int start,
                                       ElasticData elasticData) {
        elasticData.serialize(bb);
        short ipLength = (short) (
                IPv4.MIN_HEADER_LEN + UDP.HEADER_LEN + elasticData.getLength());
        bb.putShort(start + FLOW_STATE_IP_LENGTH_OFFSET, ipLength);
        short ipChecksum = IPv4.computeChecksum(
                bb.array(), start + FLOW_STATE_IP_HEADER_OFFSET,
                IPv4.MIN_HEADER_LEN, start + FLOW_STATE_IP_CHECKSUM_OFFSET);
        bb.putShort(start + FLOW_STATE_IP_CHECKSUM_OFFSET, ipChecksum);
        short udpLength = (short) (UDP.HEADER_LEN + elasticData.getLength());
        // UDP's length field offset (14 + 20 + 4)
        bb.putShort(start+ FLOW_STATE_UDP_LENGTH_OFFSET, udpLength);
    }

    private static void putFlowStateEthernetHeader() {
        try {
            Ethernet flowStateEthernet =
                    Ethernet.deserialize(CACHED_FLOW_STATE_ETHERNET_HEADER);
            IPv4 ip = (IPv4) flowStateEthernet.getPayload();
            UDP udp = (UDP) ip.getPayload();
            udp.setChecksum((short) 0);
            byte[] data = new byte[FLOW_STATE_ETHERNET_OVERHEAD];
            ByteBuffer buff = ByteBuffer.wrap(data);
            flowStateEthernet.serialize(buff);
            buff.putShort(FLOW_STATE_UDP_CHECKSUM_OFFSET, (short) 0);
            cachedFlowStateEthernetHeader = Optional.of(data);
        } catch (MalformedPacketException ex) {
            cachedFlowStateEthernetHeader =
                    Optional.of(CACHED_FLOW_STATE_ETHERNET_HEADER);
        }
    }

    @Override
    public int serialize(ByteBuffer bb) {
        int start = bb.position();
        if (!cachedFlowStateEthernetHeader.isPresent()) {
            putFlowStateEthernetHeader();
        }
        bb.put(cachedFlowStateEthernetHeader.get());
        final ElasticData elasticData = getCore();
        if (elasticData.getLength() > 0) {
            putElasticData(bb, start, elasticData);
        }
        return FLOW_STATE_ETHERNET_OVERHEAD + getElasticDataLength();
    }

    @Override
    public byte[] serialize() {
        if (payload != null) {
            payload.setParent(this);
        }
        int length = FlowStateEthernet.FLOW_STATE_ETHERNET_OVERHEAD +
                getElasticDataLength();
        if (pad && length < 60) {
            length = 60;
        }
        byte[] data = new byte[length];
        ByteBuffer bb = ByteBuffer.wrap(data);
        serialize(bb);
        if (pad) {
            Arrays.fill(data, bb.position(), data.length, (byte) 0x0);
        }
        return data;
    }

    public int getElasticDataLength() {
        IPv4 ipv4 = (IPv4) getPayload();
        UDP udp = (UDP) ipv4.getPayload();
        ElasticData elasticData = (ElasticData) udp.getPayload();
        return elasticData.getLength();
    }

    public void setElasticDataLength(int length) {
        IPv4 ipv4 = (IPv4) getPayload();
        UDP udp = (UDP) ipv4.getPayload();
        ElasticData elasticData = (ElasticData) udp.getPayload();
        elasticData.setLength(length);
    }

    public ElasticData getCore() {
        IPv4 ipv4 = (IPv4) getPayload();
        UDP udp = (UDP) ipv4.getPayload();
        return (ElasticData) udp.getPayload();
    }

    public void setCore(ElasticData data) {
        IPv4 ipv4 = (IPv4) getPayload();
        UDP udp = (UDP) ipv4.getPayload();
        data.setParent(udp);
        udp.setPayload(data);
    }
}
