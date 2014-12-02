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
 */
public class FlowStateEthernet extends Ethernet {
    public static int MTU = 1500;
    // Ethernet(IEE 802.3): http://standards.ieee.org/about/get/802/802.3.html
    // IP: https://tools.ietf.org/html/rfc791
    // UDP: http://tools.ietf.org/html/rfc768
    // GRE: http://tools.ietf.org/html/rfc2784
    // 20(IP) + 8(GRE+Key)
    public static int GRE_ENCAPUSULATION_OVERHEAD = 28;
    // 20(IP) + 8(GRE+Key) + 14(Ethernet w/o preamble and CRC) + 20(IP) + 8(UDP)
    public static int OVERHEAD = 70;
    public static int FLOW_STATE_PACKET_LENGTH = MTU - OVERHEAD;

    @Override
    protected void flush(ByteBuffer bb, byte[] payloadData) {
        super.flush(bb, payloadData, OVERHEAD + getElasticDataLength());
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
        udp.setPayload(data);
    }
}
