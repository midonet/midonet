/*
 * Copyright 2016 Midokura SARL
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

package org.midonet;

import java.nio.ByteOrder;

import org.junit.Assert;
import org.junit.Test;

public class UtilTest {

    @Test
    public void testNetworkToHost() {
        if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) {
            Assert.assertEquals(Util.networkToHost((short) 0xABCD),
                                (short) 0xCDAB);
            Assert.assertEquals(Util.networkToHost(0xABCDEFFE), 0xFEEFCDAB);
        } else {
            Assert.assertEquals(Util.networkToHost((short) 0xABCD),
                                (short) 0xABCD);
            Assert.assertEquals(Util.networkToHost(0xABCDEFFE), 0xABCDEFFE);
        }
    }

    @Test
    public void testHostToNetwork() {
        if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) {
            Assert.assertEquals(Util.hostToNetwork((short) 0xABCD),
                                (short) 0xCDAB);
            Assert.assertEquals(Util.hostToNetwork(0xABCDEFFE), 0xFEEFCDAB);
        } else {
            Assert.assertEquals(Util.hostToNetwork((short) 0xABCD),
                                (short) 0xABCD);
            Assert.assertEquals(Util.hostToNetwork(0xABCDEFFE), 0xABCDEFFE);
        }
    }

}
