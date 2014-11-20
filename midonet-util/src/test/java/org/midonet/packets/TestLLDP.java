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
import java.util.List;

import org.junit.Test;
import org.junit.Assert;

public class TestLLDP {

    private LLDP makeLLDP(LLDPTLV chassis, LLDPTLV portId, LLDPTLV ttl) {
        LLDP packet = new LLDP();
        packet.setChassisId(chassis);
        packet.setPortId(portId);
        packet.setTtl(ttl);
        return packet;
    }

    /**
     * Build an LLDP frame with both mandatory and some optional fields, then
     * serialize and deserialize to ensure correctness.
     * @throws Exception
     */
    @Test
    public void testSerialization() throws Exception {
        // Mandatory values
        LLDPTLV chassis = new LLDPTLV();
        chassis.setType(LLDPTLV.TYPE_CHASSIS_ID);
        chassis.setLength((short)7);
        chassis.setValue("chassis".getBytes());
        LLDPTLV port = new LLDPTLV();
        port.setType(LLDPTLV.TYPE_PORT_ID);
        port.setLength((short)4);
        port.setValue("port".getBytes());
        LLDPTLV ttl = new LLDPTLV();
        ttl.setType(LLDPTLV.TYPE_TTL);
        ttl.setLength((short) 3);
        ttl.setValue("ttl".getBytes());

        // Optional TLVs
        LLDPTLV opt1 = new LLDPTLV();
        opt1.setType((byte)40);
        opt1.setLength((short) 3);
        opt1.setValue("op1".getBytes()); // random content
        LLDPTLV opt2 = new LLDPTLV();
        opt2.setType((byte)5);
        opt2.setLength((short) 3);
        opt2.setValue("op2".getBytes()); // random content
        List<LLDPTLV> opts = Arrays.asList(new LLDPTLV[]{opt1, opt2});

        LLDP original = makeLLDP(chassis, port, ttl);
        original.setOptionalTLVList(opts);
        byte[] serialized = original.serialize();

        LLDP deserialized = new LLDP();
        deserialized.deserialize(ByteBuffer.wrap(serialized));

        Assert.assertTrue(original.equals(deserialized));
    }

    /**
     * Ensure that malformed LLDP frames throw the appropriate exception when
     * deserializing.
     *
     * @throws Exception
     */
    @Test(expected = MalformedPacketException.class)
    public void testPacketMissingMandatoryFields() throws Exception {
        // Mandatory values, with dodgy types, get interpreted as optional
        // when deserializing
        LLDPTLV chassis = new LLDPTLV();
        chassis.setType((byte)10);
        chassis.setLength((short)7);
        chassis.setValue("chassis".getBytes());
        LLDPTLV port = new LLDPTLV();
        port.setType((byte) 24);
        port.setLength((short)4);
        port.setValue("port".getBytes());
        LLDPTLV ttl = new LLDPTLV();
        ttl.setType((byte)3);
        ttl.setLength((short) 3);
        ttl.setValue("ttl".getBytes());

        LLDP original = makeLLDP(chassis, port, ttl);

        byte[] serialized = original.serialize();
        LLDP deserialized = new LLDP();
        deserialized.deserialize(ByteBuffer.wrap(serialized));

    }

}
