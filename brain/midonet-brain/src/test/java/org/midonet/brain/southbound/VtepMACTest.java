/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
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
