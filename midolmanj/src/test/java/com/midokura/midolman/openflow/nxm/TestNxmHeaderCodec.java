/*
 * @(#)TestNxmHeaderCodec.java        1.6 11/12/27
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.openflow.nxm;

import junit.framework.Assert;

import org.junit.Test;

public class TestNxmHeaderCodec {

    private int makeHeader(int vendor, int field, int hasMask, int len) {
        return (((vendor) << 16) | ((field) << 9) | ((hasMask) << 8) | (len));
    }

    @Test
    public void testGetType() throws Exception {
        int header = makeHeader(0x0000, 0, 1, 2);
        Assert.assertEquals(NxmType.NXM_OF_IN_PORT,
                NxmHeaderCodec.getType(header));
        header = makeHeader(0x0000, 1, 1, 6);
        Assert.assertEquals(NxmType.NXM_OF_ETH_DST,
                NxmHeaderCodec.getType(header));
        header = makeHeader(0x0000, 2, 1, 6);
        Assert.assertEquals(NxmType.NXM_OF_ETH_SRC,
                NxmHeaderCodec.getType(header));
        header = makeHeader(0x0000, 3, 1, 2);
        Assert.assertEquals(NxmType.NXM_OF_ETH_TYPE,
                NxmHeaderCodec.getType(header));
        header = makeHeader(0x0000, 4, 1, 2);
        Assert.assertEquals(NxmType.NXM_OF_VLAN_TCI,
                NxmHeaderCodec.getType(header));
        header = makeHeader(0x0000, 5, 1, 1);
        Assert.assertEquals(NxmType.NXM_OF_IP_TOS,
                NxmHeaderCodec.getType(header));
        header = makeHeader(0x0000, 6, 1, 1);
        Assert.assertEquals(NxmType.NXM_OF_IP_PROTO,
                NxmHeaderCodec.getType(header));
        header = makeHeader(0x0000, 7, 1, 4);
        Assert.assertEquals(NxmType.NXM_OF_IP_SRC,
                NxmHeaderCodec.getType(header));
        header = makeHeader(0x0000, 8, 1, 4);
        Assert.assertEquals(NxmType.NXM_OF_IP_DST,
                NxmHeaderCodec.getType(header));
        header = makeHeader(0x0000, 9, 1, 2);
        Assert.assertEquals(NxmType.NXM_OF_TCP_SRC,
                NxmHeaderCodec.getType(header));
        header = makeHeader(0x0000, 10, 1, 2);
        Assert.assertEquals(NxmType.NXM_OF_TCP_DST,
                NxmHeaderCodec.getType(header));
        header = makeHeader(0x0000, 11, 1, 2);
        Assert.assertEquals(NxmType.NXM_OF_UDP_SRC,
                NxmHeaderCodec.getType(header));
        header = makeHeader(0x0000, 12, 1, 2);
        Assert.assertEquals(NxmType.NXM_OF_UDP_DST,
                NxmHeaderCodec.getType(header));
        header = makeHeader(0x0000, 13, 1, 1);
        Assert.assertEquals(NxmType.NXM_OF_ICMP_TYPE,
                NxmHeaderCodec.getType(header));
        header = makeHeader(0x0000, 14, 1, 1);
        Assert.assertEquals(NxmType.NXM_OF_ICMP_CODE,
                NxmHeaderCodec.getType(header));
        header = makeHeader(0x0000, 15, 1, 2);
        Assert.assertEquals(NxmType.NXM_OF_ARP_OP,
                NxmHeaderCodec.getType(header));
        header = makeHeader(0x0000, 16, 1, 4);
        Assert.assertEquals(NxmType.NXM_OF_ARP_SPA,
                NxmHeaderCodec.getType(header));
        header = makeHeader(0x0000, 17, 1, 4);
        Assert.assertEquals(NxmType.NXM_OF_ARP_TPA,
                NxmHeaderCodec.getType(header));
        header = makeHeader(0x0001, 16, 1, 8);
        Assert.assertEquals(NxmType.NXM_NX_TUN_ID,
                NxmHeaderCodec.getType(header));
    }

    @Test
    public void testGetLength() throws Exception {
        int header = makeHeader(0x0000, 0, 1, 2);
        Assert.assertEquals(2, NxmHeaderCodec.getLength(header));
        header = makeHeader(0x0000, 1, 1, 6);
        Assert.assertEquals(6, NxmHeaderCodec.getLength(header));
        header = makeHeader(0x0000, 2, 1, 6);
        Assert.assertEquals(6, NxmHeaderCodec.getLength(header));
        header = makeHeader(0x0000, 3, 1, 2);
        Assert.assertEquals(2, NxmHeaderCodec.getLength(header));
        header = makeHeader(0x0000, 4, 1, 2);
        Assert.assertEquals(2, NxmHeaderCodec.getLength(header));
        header = makeHeader(0x0000, 5, 1, 1);
        Assert.assertEquals(1, NxmHeaderCodec.getLength(header));
        header = makeHeader(0x0000, 6, 1, 1);
        Assert.assertEquals(1, NxmHeaderCodec.getLength(header));
        header = makeHeader(0x0000, 7, 1, 4);
        Assert.assertEquals(4, NxmHeaderCodec.getLength(header));
        header = makeHeader(0x0000, 8, 1, 4);
        Assert.assertEquals(4, NxmHeaderCodec.getLength(header));
        header = makeHeader(0x0000, 9, 1, 2);
        Assert.assertEquals(2, NxmHeaderCodec.getLength(header));
        header = makeHeader(0x0000, 10, 1, 2);
        Assert.assertEquals(2, NxmHeaderCodec.getLength(header));
        header = makeHeader(0x0000, 11, 1, 2);
        Assert.assertEquals(2, NxmHeaderCodec.getLength(header));
        header = makeHeader(0x0000, 12, 1, 2);
        Assert.assertEquals(2, NxmHeaderCodec.getLength(header));
        header = makeHeader(0x0000, 13, 1, 1);
        Assert.assertEquals(1, NxmHeaderCodec.getLength(header));
        header = makeHeader(0x0000, 14, 1, 1);
        Assert.assertEquals(1, NxmHeaderCodec.getLength(header));
        header = makeHeader(0x0000, 15, 1, 2);
        Assert.assertEquals(2, NxmHeaderCodec.getLength(header));
        header = makeHeader(0x0000, 16, 1, 4);
        Assert.assertEquals(4, NxmHeaderCodec.getLength(header));
        header = makeHeader(0x0000, 17, 1, 4);
        Assert.assertEquals(4, NxmHeaderCodec.getLength(header));
        header = makeHeader(0x0001, 16, 1, 8);
        Assert.assertEquals(8, NxmHeaderCodec.getLength(header));
    }

    @Test
    public void testHasMask() throws Exception {
        int header = makeHeader(0x0000, 0, 1, 2);
        Assert.assertTrue(NxmHeaderCodec.hasMask(header));
        header = makeHeader(0x0000, 1, 1, 6);
        Assert.assertTrue(NxmHeaderCodec.hasMask(header));
        header = makeHeader(0x0000, 2, 1, 6);
        Assert.assertTrue(NxmHeaderCodec.hasMask(header));
        header = makeHeader(0x0000, 3, 1, 2);
        Assert.assertTrue(NxmHeaderCodec.hasMask(header));
        header = makeHeader(0x0000, 4, 1, 2);
        Assert.assertTrue(NxmHeaderCodec.hasMask(header));
        header = makeHeader(0x0000, 5, 1, 1);
        Assert.assertTrue(NxmHeaderCodec.hasMask(header));
        header = makeHeader(0x0000, 6, 1, 1);
        Assert.assertTrue(NxmHeaderCodec.hasMask(header));
        header = makeHeader(0x0000, 7, 1, 4);
        Assert.assertTrue(NxmHeaderCodec.hasMask(header));
        header = makeHeader(0x0000, 8, 1, 4);
        Assert.assertTrue(NxmHeaderCodec.hasMask(header));
        header = makeHeader(0x0000, 9, 1, 2);
        Assert.assertTrue(NxmHeaderCodec.hasMask(header));
        header = makeHeader(0x0000, 10, 1, 2);
        Assert.assertTrue(NxmHeaderCodec.hasMask(header));
        header = makeHeader(0x0000, 11, 1, 2);
        Assert.assertTrue(NxmHeaderCodec.hasMask(header));
        header = makeHeader(0x0000, 12, 1, 2);
        Assert.assertTrue(NxmHeaderCodec.hasMask(header));
        header = makeHeader(0x0000, 13, 1, 1);
        Assert.assertTrue(NxmHeaderCodec.hasMask(header));
        header = makeHeader(0x0000, 14, 1, 1);
        Assert.assertTrue(NxmHeaderCodec.hasMask(header));
        header = makeHeader(0x0000, 15, 1, 2);
        Assert.assertTrue(NxmHeaderCodec.hasMask(header));
        header = makeHeader(0x0000, 16, 1, 4);
        Assert.assertTrue(NxmHeaderCodec.hasMask(header));
        header = makeHeader(0x0000, 17, 1, 4);
        Assert.assertTrue(NxmHeaderCodec.hasMask(header));
        header = makeHeader(0x0001, 16, 1, 8);
        Assert.assertTrue(NxmHeaderCodec.hasMask(header));

        header = makeHeader(0x0000, 0, 0, 2);
        Assert.assertFalse(NxmHeaderCodec.hasMask(header));
        header = makeHeader(0x0000, 1, 0, 6);
        Assert.assertFalse(NxmHeaderCodec.hasMask(header));
        header = makeHeader(0x0000, 2, 0, 6);
        Assert.assertFalse(NxmHeaderCodec.hasMask(header));
        header = makeHeader(0x0000, 3, 0, 2);
        Assert.assertFalse(NxmHeaderCodec.hasMask(header));
        header = makeHeader(0x0000, 4, 0, 2);
        Assert.assertFalse(NxmHeaderCodec.hasMask(header));
        header = makeHeader(0x0000, 5, 0, 1);
        Assert.assertFalse(NxmHeaderCodec.hasMask(header));
        header = makeHeader(0x0000, 6, 0, 1);
        Assert.assertFalse(NxmHeaderCodec.hasMask(header));
        header = makeHeader(0x0000, 7, 0, 4);
        Assert.assertFalse(NxmHeaderCodec.hasMask(header));
        header = makeHeader(0x0000, 8, 0, 4);
        Assert.assertFalse(NxmHeaderCodec.hasMask(header));
        header = makeHeader(0x0000, 9, 0, 2);
        Assert.assertFalse(NxmHeaderCodec.hasMask(header));
        header = makeHeader(0x0000, 10, 0, 2);
        Assert.assertFalse(NxmHeaderCodec.hasMask(header));
        header = makeHeader(0x0000, 11, 0, 2);
        Assert.assertFalse(NxmHeaderCodec.hasMask(header));
        header = makeHeader(0x0000, 12, 0, 2);
        Assert.assertFalse(NxmHeaderCodec.hasMask(header));
        header = makeHeader(0x0000, 13, 0, 1);
        Assert.assertFalse(NxmHeaderCodec.hasMask(header));
        header = makeHeader(0x0000, 14, 0, 1);
        Assert.assertFalse(NxmHeaderCodec.hasMask(header));
        header = makeHeader(0x0000, 15, 0, 2);
        Assert.assertFalse(NxmHeaderCodec.hasMask(header));
        header = makeHeader(0x0000, 16, 0, 4);
        Assert.assertFalse(NxmHeaderCodec.hasMask(header));
        header = makeHeader(0x0000, 17, 0, 4);
        Assert.assertFalse(NxmHeaderCodec.hasMask(header));
        header = makeHeader(0x0001, 16, 0, 8);
        Assert.assertFalse(NxmHeaderCodec.hasMask(header));
    }
}
