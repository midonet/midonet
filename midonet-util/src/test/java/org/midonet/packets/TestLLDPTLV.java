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
import java.util.Collection;

import org.junit.Assert;
import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Enclosed.class)
public class TestLLDPTLV {

    @RunWith(Parameterized.class)
    public static class TestLLDPTLVValidPacket {

        private final byte[] input;
        private final LLDPTLV expected;

        public TestLLDPTLVValidPacket(byte[] input, LLDPTLV expected) {
            this.input = input;
            this.expected = expected;
        }

        private static LLDPTLV copyTlv(LLDPTLV tlv) {
            LLDPTLV copy = new LLDPTLV();
            copy.setType(tlv.getType());
            copy.setLength(tlv.getLength());
            copy.setValue(tlv.getValue());
            return copy;
        }

        @SuppressWarnings("unchecked")
        @Parameters
        public static Collection<Object[]> data() {
            byte[] header = new byte[] { 0x02, 0x00 };
            LLDPTLV tlv = new LLDPTLV();
            tlv.setType(LLDPTLV.TYPE_CHASSIS_ID);
            tlv.setLength((short) 0x00);
            tlv.setValue(null);

            byte[] oneByte = Arrays.copyOf(header, header.length + 1);
            oneByte[1] = 0x01;
            byte[] oneByteVal = Arrays.copyOfRange(oneByte, 2, oneByte.length);
            LLDPTLV oneByteTlv = copyTlv(tlv).setLength((short) 0x01).setValue(
                    oneByteVal);

            byte[] maxBytes = Arrays.copyOf(header, header.length + 0x1FF);
            maxBytes[0] = 0x03;
            maxBytes[1] = (byte) 0xFF;
            byte[] maxBytesVal = Arrays.copyOfRange(maxBytes, 2,
                    maxBytes.length);
            LLDPTLV maxBytesTlv = copyTlv(tlv).setLength((short) 0x1FF)
                    .setValue(maxBytesVal);

            // Buffer larger than necessary - small packet
            byte[] valueLargerThanSpecifiedSmall = Arrays.copyOf(header,
                    header.length + 1);
            LLDPTLV valueLargerThanSpecifiedSmallTlv = copyTlv(tlv);

            // Buffer larger than necessary - large packet
            byte[] valueLargerThanSpecifiedBig = Arrays.copyOf(header,
                    header.length + 0x1FF);
            valueLargerThanSpecifiedBig[0] = 0x03;
            valueLargerThanSpecifiedBig[1] = (byte) 0xFE;
            byte[] resultData = Arrays.copyOfRange(valueLargerThanSpecifiedBig,
                    2, header.length + 0x1FE);
            LLDPTLV valueLargerThanSpecifiedBigTlv = copyTlv(tlv).setLength(
                    (short) 0x1FE).setValue(resultData);

            Object[][] input = new Object[][] {
                    { header, tlv },
                    { oneByte, oneByteTlv },
                    { maxBytes, maxBytesTlv },
                    { valueLargerThanSpecifiedSmall,
                            valueLargerThanSpecifiedSmallTlv },
                    { valueLargerThanSpecifiedBig,
                            valueLargerThanSpecifiedBigTlv } };

            return Arrays.asList(input);
        }

        @Test
        public void TestDeserialize() throws Exception {
            ByteBuffer buff = ByteBuffer.wrap(this.input);
            LLDPTLV tlv = new LLDPTLV();
            tlv.deserialize(buff);

            Assert.assertEquals(expected.getType(), tlv.getType());
            Assert.assertEquals(expected.getLength(), tlv.getLength());
            Assert.assertArrayEquals(expected.getValue(), tlv.getValue());
        }
    }

    @RunWith(Parameterized.class)
    public static class TestLLDPTLVMalformedPacket {

        private final byte[] input;

        public TestLLDPTLVMalformedPacket(byte[] input) {
            this.input = input;
        }

        @SuppressWarnings("unchecked")
        @Parameters
        public static Collection<Object[]> data() {
            byte[] header = new byte[] { 0x02, 0x00 };

            byte[] headerCutOff = Arrays.copyOf(header, 1);

            // One byte data, two bytes len speicfied
            byte[] valueCutOffSmall = Arrays.copyOf(header, header.length + 1);
            valueCutOffSmall[1] = 0x02;

            // Max byte data, max-1 len speicfied
            byte[] valueCutOffBig = Arrays
                    .copyOf(header, header.length + 0x1FE);
            valueCutOffBig[0] = 0x03;
            valueCutOffBig[1] = (byte) 0xFF;

            Object[][] data = new Object[][] { { new byte[] {} },
                    { headerCutOff }, { valueCutOffSmall }, { valueCutOffBig } };

            return Arrays.asList(data);
        }

        @Test(expected = MalformedPacketException.class)
        public void TestDeserialize() throws Exception {
            ByteBuffer buff = ByteBuffer.wrap(this.input);
            LLDPTLV tlv = new LLDPTLV();
            tlv.deserialize(buff);
        }
    }
}
