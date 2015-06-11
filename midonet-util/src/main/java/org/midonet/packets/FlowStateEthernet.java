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
import java.util.Arrays;

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
public class FlowStateEthernet extends Ethernet {
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
    public final static byte FLOW_STATE_IP_FLAGS = (byte) 0b010;
    public final static short FLOW_STATE_IP_FRAGMENT_OFFSET = (short) 0;
    // TODO(tfukushima): Get the accurate TTL and use it if possible.
    public final static byte FLOW_STATE_IP_TTL = (byte) 64;
    public final static byte FLOW_STATE_IP_PROTOCOL = (byte) 0x11;
    public final static IPv4Addr FLOW_STATE_IP_SRC_ADDRESS =
            IPv4Addr.fromString("169.254.15.1");
    public final static IPv4Addr FLOW_STATE_IP_DST_ADDRESS =
            IPv4Addr.fromString("169.254.15.2");
    public final static int FLOW_STATE_UDP_PORT = 2925;

    private static byte[] cachedFlowStateEthernetHeader;
    private static IPv4 cachedIpv4;

    private ElasticData core = new ElasticData(new byte[] {});

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
        bb.putShort(start+ FLOW_STATE_UDP_LENGTH_OFFSET, udpLength);
    }

    private static void putFlowStateEthernetHeader() {
        Ethernet flowStateEthernet = new Ethernet();
        flowStateEthernet.setSourceMACAddress(
                MAC.fromString(FLOW_STATE_ETHERNET_SOURCE_MAC));
        flowStateEthernet.setDestinationMACAddress(
                MAC.fromString(FLOW_STATE_ETHERNET_DESTINATION_MAC));
        flowStateEthernet.setEtherType(FLOW_STATE_ETHERNET_TYPE);

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
        ipv4.setParent(flowStateEthernet);
        flowStateEthernet.setPayload(ipv4);
        cachedIpv4 = ipv4;

        UDP udp = new UDP();
        udp.setSourcePort((short) FLOW_STATE_UDP_PORT);
        udp.setDestinationPort((short) FLOW_STATE_UDP_PORT);
        udp.setParent(ipv4);
        ipv4.setPayload(udp);

        Data emptyData = new Data(new byte[] {});
        emptyData.setParent(udp);
        udp.setPayload(emptyData);

        byte[] serialized = new byte[FLOW_STATE_ETHERNET_OVERHEAD];
        ByteBuffer buff = ByteBuffer.wrap(serialized);
        flowStateEthernet.serialize(buff);
        // UDP checksum is calculated during the serialization, so set it
        // zero here.
        buff.putShort(FLOW_STATE_UDP_CHECKSUM_OFFSET, (short) 0);
        cachedFlowStateEthernetHeader = serialized;
    }

    static {
        putFlowStateEthernetHeader();
    }

    public FlowStateEthernet() {
        super();
        this.setSourceMACAddress(
                MAC.fromString(FLOW_STATE_ETHERNET_SOURCE_MAC));
        this.setDestinationMACAddress(
                MAC.fromString(FLOW_STATE_ETHERNET_DESTINATION_MAC));
        this.setEtherType(FLOW_STATE_ETHERNET_TYPE);
        // FlowStateEthernet instance points the same cached payload always but
        // the cached payload refers back to the same FlowStateEthernet
        // instance. The opposite case doesn't hold.
        this.setPayload(cachedIpv4);
    }

    @Override
    public int getPayloadLength() {
        return getElasticDataLength();
    }

    @Override
    public int serialize(ByteBuffer bb) {
        int start = bb.position();
        bb.put(cachedFlowStateEthernetHeader);
        putElasticData(bb, start, core);
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
        return core.getLength();
    }

    public void setElasticDataLength(int length) {
        core.setLength(length);
    }

    public ElasticData getCore() {
        return core;
    }

    public void setCore(ElasticData data) {
        core = data;
    }
}
