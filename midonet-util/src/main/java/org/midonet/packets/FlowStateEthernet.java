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
import java.util.Iterator;

/**
 * FlowStateEthernet is an ethernet frame contains a flow state UDP packet,
 * which length is flexible.
 */
public class FlowStateEthernet extends Ethernet {
    // 0               8               16              24             32
    // Ethernet Frame Header
    // +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
    // |                       Source MAC Address...                   |
    // +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
    // | ...(cont)     |     Destination MAC Address...                |
    // +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
    // |           ...(cont)           |       EtherType/Length        |
    // +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
    // IP header
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
    // UDP header
    // +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
    // |           Source Port         |        Destination Port       |
    // +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
    // |             Length            |             Checksum          |
    // +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
    public static int MTU = 1500;
    // Ethernet(IEE 802.3): http://standards.ieee.org/about/get/802/802.3.html
    // IP: https://tools.ietf.org/html/rfc791
    // UDP: http://tools.ietf.org/html/rfc768
    // GRE: http://tools.ietf.org/html/rfc2784
    // 20(IP) + 8(GRE+Key)
    public static int GRE_ENCAPUSULATION_OVERHEAD = 28;
    // 20(IP) + 8(GRE+Key) + 14(Ethernet w/o preamble and CRC) + 20(IP) + 8(UDP)
    public static int OVERHEAD = 70;
    // 14(Ethernet) + 20(IP) + 8(UDP)
    // public static int FLOW_STATE_ETHERNET_OVERHEAD = 28;
    public static int FLOW_STATE_ETHERNET_OVERHEAD = 42;
    public static int FLOW_STATE_PACKET_LENGTH = MTU - OVERHEAD;

    private byte[] cachedFlowStateEthernetHeader =
            new byte[FLOW_STATE_ETHERNET_OVERHEAD];

    static private void putUdpHeader(ByteBuffer bb, UDP udp) {
        bb.putShort((short) udp.getSourcePort());
        bb.putShort((short) udp.getDestinationPort());
        bb.putShort((short) udp.getLength());
        bb.putShort((short) 0);
    }

    static private void putIpv4Header(ByteBuffer bb, IPv4 iPv4) {
        bb.put((byte) (((iPv4.getVersion() & 0xf) << 4) |
                (iPv4.getHeaderLength() & 0xf)));
        bb.put(iPv4.getDiffServ());
        bb.putShort((short) iPv4.getTotalLength());
        bb.putShort(iPv4.getIdentification());
        bb.putShort((short) (((iPv4.getFlags() & 0x7) << 13) |
                (iPv4.getFragmentOffset() & 0x1fff)));
        bb.put(iPv4.getTtl());
        bb.put(iPv4.getProtocol());
        bb.putShort(iPv4.getChecksum());
        bb.putInt(iPv4.getSourceAddress());
        bb.putInt(iPv4.getDestinationAddress());
        if ((iPv4.getHeaderLength() > 5) && (iPv4.getOptions() != null)) {
            bb.put(iPv4.getOptions());
        }
    }

    static private void putFlowStateEthernetHeader(ByteBuffer bb,
                                                   FlowStateEthernet eth) {
        bb.put(eth.getDestinationMACAddress().getAddress());
        bb.put(eth.getSourceMACAddress().getAddress());
        for (Iterator<Short> it = eth.getVlanIDs().iterator(); it.hasNext();) {
            short vlanID = it.next();
            // if it's the last tag we need to use the VLAN_TAGGED_FRAME type
            bb.putShort(it.hasNext() ? PROVIDER_BRIDGING_TAG : VLAN_TAGGED_FRAME);
            bb.putShort((short) ((eth.getPriorityCode() << 13) |
                    (vlanID & 0x0fff)));
        }
        bb.putShort(eth.getEtherType());
    }

    private void putHeader(ByteBuffer bb) {
        putFlowStateEthernetHeader(bb, this);
        final IPv4 iPv4 = (IPv4) payload;
        putIpv4Header(bb, iPv4);
        final UDP udp = (UDP) iPv4.getPayload();
        putUdpHeader(bb, udp);
    }

    private void putElasticData(ByteBuffer bb, ElasticData elasticData) {
        elasticData.serialize(bb);
        int currentPosition = bb.position();
        short ipLength = (short) (20 + 8 + elasticData.getLength());
        bb.position(16);  // IP's length field offset (14 + 2)
        bb.putShort(ipLength);
        short udpLength = (short) (8 + elasticData.getLength());
        bb.position(38);  // UDP's length field offset (14 + 20 + 4)
        bb.putShort(udpLength);
        bb.position(currentPosition);
    }

    @Override
    public IPacket setPayload(IPacket payload) {
        this.payload = payload;
        payload.setParent(this);
        ByteBuffer bb = ByteBuffer.wrap(cachedFlowStateEthernetHeader);
        putHeader(bb);
        return this;
    }

    @Override
    public int serialize(ByteBuffer bb) {
        bb.put(cachedFlowStateEthernetHeader);
        final ElasticData elasticData = (ElasticData) getCore();
        putElasticData(bb, elasticData);
        return FLOW_STATE_ETHERNET_OVERHEAD + getElasticDataLength();
    }

    @Override
    public byte[] serialize() {
        final ElasticData elasticData = (ElasticData) getCore();
        final int payloadSize = getElasticDataLength();
        if (payload != null) {
            payload.setParent(this);
        }
        int headerLength = 14 + (vlanIDs.size() * 4) + 20 + 8;
        int length = headerLength + payloadSize;
        if (pad && length < 60) {
            length = 60;
        }
        byte[] data = new byte[length];
        ByteBuffer bb = ByteBuffer.wrap(data);
        bb.put(cachedFlowStateEthernetHeader);
        if (elasticData.getLength() > 0) {
            putElasticData(bb, elasticData);
        }
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

    public Data getCore() {
        IPv4 ipv4 = (IPv4) getPayload();
        UDP udp = (UDP) ipv4.getPayload();
        return (Data) udp.getPayload();
    }

    public void setCore(Data data) {
        IPv4 ipv4 = (IPv4) getPayload();
        UDP udp = (UDP) ipv4.getPayload();
        data.setParent(udp);
        udp.setPayload(data);
    }
}
