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

import org.junit.Test;

import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertThat;

public class TestVXLAN {
    private byte[] data = new byte[] {
        (byte) 0x00, (byte) 0x16, (byte) 0x3e, (byte) 0x08, (byte) 0x71,
        (byte) 0xcf, (byte) 0x36, (byte) 0xdc, (byte) 0x85, (byte) 0x1e,
        (byte) 0xb3, (byte) 0x40, (byte) 0x08, (byte) 0x00, (byte) 0x45,
        (byte) 0x00, (byte) 0x00, (byte) 0x86, (byte) 0xd2, (byte) 0xc2,
        (byte) 0x40, (byte) 0x00, (byte) 0x40, (byte) 0x11, (byte) 0x51,
        (byte) 0x50, (byte) 0xc0, (byte) 0xa8, (byte) 0xcb, (byte) 0x01,
        (byte) 0xc0, (byte) 0xa8, (byte) 0xca, (byte) 0x01, (byte) 0xb0,
        (byte) 0x5d, (byte) 0x12, (byte) 0xb5, (byte) 0x00, (byte) 0x72,
        (byte) 0x35, (byte) 0x3E, (byte) 0x08, (byte) 0x00, (byte) 0x00,
        (byte) 0x00, (byte) 0x00, (byte) 0xdf, (byte) 0x23, (byte) 0x00,
        (byte) 0x00, (byte) 0x30, (byte) 0x88, (byte) 0x01, (byte) 0x00,
        (byte) 0x02, (byte) 0x00, (byte) 0x16, (byte) 0x3e, (byte) 0x37,
        (byte) 0xf6, (byte) 0x04, (byte) 0x08, (byte) 0x00, (byte) 0x45,
        (byte) 0x00, (byte) 0x00, (byte) 0x54, (byte) 0x00, (byte) 0x00,
        (byte) 0x40, (byte) 0x00, (byte) 0x40, (byte) 0x01, (byte) 0x23,
        (byte) 0x4f, (byte) 0xc0, (byte) 0xa8, (byte) 0xcb, (byte) 0x03,
        (byte) 0xc0, (byte) 0xa8, (byte) 0xcb, (byte) 0x05, (byte) 0x08,
        (byte) 0x00, (byte) 0x05, (byte) 0xed, (byte) 0x05, (byte) 0x0c,
        (byte) 0x00, (byte) 0x02, (byte) 0xfd, (byte) 0xe2, (byte) 0x97,
        (byte) 0x51, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
        (byte) 0x96, (byte) 0xfd, (byte) 0x02, (byte) 0x00, (byte) 0x00,
        (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x10, (byte) 0x11,
        (byte) 0x12, (byte) 0x13, (byte) 0x14, (byte) 0x15, (byte) 0x16,
        (byte) 0x17, (byte) 0x18, (byte) 0x19, (byte) 0x1a, (byte) 0x1b,
        (byte) 0x1c, (byte) 0x1d, (byte) 0x1e, (byte) 0x1f, (byte) 0x20,
        (byte) 0x21, (byte) 0x22, (byte) 0x23, (byte) 0x24, (byte) 0x25,
        (byte) 0x26, (byte) 0x27, (byte) 0x28, (byte) 0x29, (byte) 0x2a,
        (byte) 0x2b, (byte) 0x2c, (byte) 0x2d, (byte) 0x2e, (byte) 0x2f,
        (byte) 0x30, (byte) 0x31, (byte) 0x32, (byte) 0x33, (byte) 0x34,
        (byte) 0x35, (byte) 0x36, (byte) 0x37
    };

    @Test
    public void testSerialization() throws Exception {
        Ethernet outerEth = new Ethernet();
        outerEth.deserialize(ByteBuffer.wrap(data));
        IPv4 outerIp = (IPv4) outerEth.getPayload();
        UDP outerUdp = (UDP) outerIp.getPayload();
        assertThat(outerUdp.getDestinationPort(), is(UDP.VXLAN));
        assertThat(outerUdp.getSourcePort(), is(45149));
        VXLAN vxlan = (VXLAN) outerUdp.getPayload();
        assertThat(vxlan.getVni(), is(57123));

        Ethernet innerEth = (Ethernet) vxlan.getPayload();
        IPv4 innerIp = (IPv4) innerEth.getPayload();
        ICMP innerIcmp = (ICMP) innerIp.getPayload();
        assertThat(innerIcmp.getCode(), is(ICMP.CODE_NONE));
        assertThat(innerIcmp.getType(), is(ICMP.TYPE_ECHO_REQUEST));
        assertThat(innerIcmp.getIdentifier(), is((short)1292));

        assertThat(outerEth.serialize(), is(data));
    }
}
