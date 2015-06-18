/*
 * Copyright 2015 Midokura SARL
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
import java.util.Random;

import org.junit.Assert;
import org.junit.Test;

/**
 * Created by Pino on 26/7/15.
 */
public class TestVXLAN {

    @Test
    public void testSerialization() throws Exception {
        // SSH packet (starting with IPv4 headers) captured with tcpdump.
        byte[] data = new byte[] { (byte) 0x45, (byte) 0x10, (byte) 0x00,
                                   (byte) 0x64, (byte) 0xec, (byte) 0xbc, (byte) 0x40,
                                   (byte) 0x00, (byte) 0x40, (byte) 0x06, (byte) 0x60,
                                   (byte) 0xcf, (byte) 0xc0, (byte) 0xa8, (byte) 0x01,
                                   (byte) 0x85, (byte) 0x0e, (byte) 0x80, (byte) 0x1c,
                                   (byte) 0x4b, (byte) 0xe5, (byte) 0x0e, (byte) 0x00,
                                   (byte) 0x16, (byte) 0x8d, (byte) 0x3a, (byte) 0x5d,
                                   (byte) 0x09, (byte) 0x0f, (byte) 0x95, (byte) 0xc4,
                                   (byte) 0xe3, (byte) 0x80, (byte) 0x18, (byte) 0xff,
                                   (byte) 0xff, (byte) 0x0a, (byte) 0x46, (byte) 0x00,
                                   (byte) 0x00, (byte) 0x01, (byte) 0x01, (byte) 0x08,
                                   (byte) 0x0a, (byte) 0x2f, (byte) 0x1c, (byte) 0x20,
                                   (byte) 0x06, (byte) 0x0e, (byte) 0x20, (byte) 0x8d,
                                   (byte) 0x38, (byte) 0xbd, (byte) 0xfc, (byte) 0xd7,
                                   (byte) 0xa6, (byte) 0x8d, (byte) 0xc3, (byte) 0x06,
                                   (byte) 0x93, (byte) 0x5f, (byte) 0xdf, (byte) 0x0e,
                                   (byte) 0x11, (byte) 0x49, (byte) 0x4a, (byte) 0x68,
                                   (byte) 0xdc, (byte) 0x30, (byte) 0x8a, (byte) 0x2b,
                                   (byte) 0xdc, (byte) 0xb2, (byte) 0xb2, (byte) 0xd4,
                                   (byte) 0x0e, (byte) 0xea, (byte) 0xb5, (byte) 0x1e,
                                   (byte) 0xf9, (byte) 0xd0, (byte) 0xdf, (byte) 0x26,
                                   (byte) 0xbf, (byte) 0x56, (byte) 0xa6, (byte) 0x65,
                                   (byte) 0x36, (byte) 0x07, (byte) 0x9c, (byte) 0x95,
                                   (byte) 0x23, (byte) 0x9d, (byte) 0xd3, (byte) 0xeb,
                                   (byte) 0xa7, (byte) 0x3c, (byte) 0x68, (byte) 0xa3,
                                   (byte) 0xe3 };
        IPv4 ipPkt = new IPv4();
        // Deserialize the whole packet.
        ByteBuffer bb = ByteBuffer.wrap(data, 0, data.length);
        ipPkt.deserialize(bb);
        // Basic sanity check: IPv4 contains a TCP packet to port 22 (ssh).
        Assert.assertEquals(TCP.PROTOCOL_NUMBER, ipPkt.getProtocol());
        TCP udpPkt = (TCP) ipPkt.getPayload();
        Assert.assertEquals(22, udpPkt.getDestinationPort());

        Ethernet innerEth = new Ethernet();
        innerEth.destinationMACAddress = new byte[6];
        innerEth.sourceMACAddress = new byte[6];
        innerEth.priorityCode = (byte) 1;
        innerEth.etherType = IPv4.ETHERTYPE;
        Random r = new Random();
        innerEth.setPayload(ipPkt);
        r.nextBytes(innerEth.destinationMACAddress);
        r.nextBytes(innerEth.sourceMACAddress);

        VXLAN vxlan = new VXLAN();
        vxlan.setVni(30);
        vxlan.setPayload(innerEth);
        UDP udp = new UDP();
    }
}