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
package org.midonet.brain.southbound;

import org.junit.Test;

import org.midonet.brain.southbound.vtep.VtepMAC;
import org.midonet.packets.MAC;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class VtepMACTest {

    @Test
    public void testEquals() {
        MAC m1 = MAC.fromString("aa:bb:cc:dd:ee:11");
        MAC m2 = MAC.fromString("aa:bb:cc:dd:ee:22");
        VtepMAC vm1 = VtepMAC.fromMac(m1);
        VtepMAC vm2 = VtepMAC.fromMac(m2);
        assertNotSame(vm1, vm2);
        assertNotSame(vm1, VtepMAC.UNKNOWN_DST);
        assertEquals(VtepMAC.UNKNOWN_DST, VtepMAC.UNKNOWN_DST);
    }

    @Test
    public void testToString() {
        MAC m1 = MAC.fromString("aa:bb:cc:dd:ee:11");
        VtepMAC vm1 = VtepMAC.fromMac(m1);
        assertEquals(m1.toString(), vm1.toString());
        assertEquals("unknown-dst", VtepMAC.UNKNOWN_DST.toString());
    }

    @Test
    public void testIeee802() {
        MAC m1 = MAC.fromString("aa:bb:cc:dd:ee:11");
        VtepMAC vm1 = VtepMAC.fromMac(m1);
        assertTrue(vm1.isIEEE802());
        assertEquals(vm1.IEEE802(), m1);
        assertFalse(VtepMAC.UNKNOWN_DST.isIEEE802());
        assertNull(VtepMAC.UNKNOWN_DST.IEEE802());
    }

    @Test
    public void isUnknownDstMcast() {
        assertTrue(VtepMAC.UNKNOWN_DST.isMcast());
    }

}
