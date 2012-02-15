/*
 * @(#)TestNxmType.java        1.6 11/12/27
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.openflow.nxm;

import junit.framework.Assert;

import org.junit.Test;

public class TestNxmType {

    private int makeType(int vendor, int field) {
        return (vendor << 7) | field;
    }

    private int makeHeader(int vendor, int field, int hasMask, int len) {
        return (((vendor) << 16) | ((field) << 9) | ((hasMask) << 8) | (len));
    }

    @Test
    public void testVals() throws Exception {
        int type = makeType(0x0000, 0);
        Assert.assertEquals(type, NxmType.NXM_OF_IN_PORT.getVal());
        type = makeType(0x0000, 1);
        Assert.assertEquals(type, NxmType.NXM_OF_ETH_DST.getVal());
        type = makeType(0x0000, 2);
        Assert.assertEquals(type, NxmType.NXM_OF_ETH_SRC.getVal());
        type = makeType(0x0000, 3);
        Assert.assertEquals(type, NxmType.NXM_OF_ETH_TYPE.getVal());
        type = makeType(0x0000, 4);
        Assert.assertEquals(type, NxmType.NXM_OF_VLAN_TCI.getVal());
        type = makeType(0x0000, 5);
        Assert.assertEquals(type, NxmType.NXM_OF_IP_TOS.getVal());
        type = makeType(0x0000, 6);
        Assert.assertEquals(type, NxmType.NXM_OF_IP_PROTO.getVal());
        type = makeType(0x0000, 7);
        Assert.assertEquals(type, NxmType.NXM_OF_IP_SRC.getVal());
        type = makeType(0x0000, 8);
        Assert.assertEquals(type, NxmType.NXM_OF_IP_DST.getVal());
        type = makeType(0x0000, 9);
        Assert.assertEquals(type, NxmType.NXM_OF_TCP_SRC.getVal());
        type = makeType(0x0000, 10);
        Assert.assertEquals(type, NxmType.NXM_OF_TCP_DST.getVal());
        type = makeType(0x0000, 11);
        Assert.assertEquals(type, NxmType.NXM_OF_UDP_SRC.getVal());
        type = makeType(0x0000, 12);
        Assert.assertEquals(type, NxmType.NXM_OF_UDP_DST.getVal());
        type = makeType(0x0000, 13);
        Assert.assertEquals(type, NxmType.NXM_OF_ICMP_TYPE.getVal());
        type = makeType(0x0000, 14);
        Assert.assertEquals(type, NxmType.NXM_OF_ICMP_CODE.getVal());
        type = makeType(0x0000, 15);
        Assert.assertEquals(type, NxmType.NXM_OF_ARP_OP.getVal());
        type = makeType(0x0000, 16);
        Assert.assertEquals(type, NxmType.NXM_OF_ARP_SPA.getVal());
        type = makeType(0x0000, 17);
        Assert.assertEquals(type, NxmType.NXM_OF_ARP_TPA.getVal());
        type = makeType(0x0001, 16);
        Assert.assertEquals(type, NxmType.NXM_NX_TUN_ID.getVal());
    }

    @Test
    public void testVendors() throws Exception {
        Assert.assertEquals(0, NxmType.NXM_OF_IN_PORT.getVendorId());
        Assert.assertEquals(0, NxmType.NXM_OF_ETH_DST.getVendorId());
        Assert.assertEquals(0, NxmType.NXM_OF_ETH_SRC.getVendorId());
        Assert.assertEquals(0, NxmType.NXM_OF_ETH_TYPE.getVendorId());
        Assert.assertEquals(0, NxmType.NXM_OF_VLAN_TCI.getVendorId());
        Assert.assertEquals(0, NxmType.NXM_OF_IP_TOS.getVendorId());
        Assert.assertEquals(0, NxmType.NXM_OF_IP_PROTO.getVendorId());
        Assert.assertEquals(0, NxmType.NXM_OF_IP_SRC.getVendorId());
        Assert.assertEquals(0, NxmType.NXM_OF_IP_DST.getVendorId());
        Assert.assertEquals(0, NxmType.NXM_OF_TCP_SRC.getVendorId());
        Assert.assertEquals(0, NxmType.NXM_OF_TCP_DST.getVendorId());
        Assert.assertEquals(0, NxmType.NXM_OF_UDP_SRC.getVendorId());
        Assert.assertEquals(0, NxmType.NXM_OF_UDP_DST.getVendorId());
        Assert.assertEquals(0, NxmType.NXM_OF_ICMP_TYPE.getVendorId());
        Assert.assertEquals(0, NxmType.NXM_OF_ICMP_CODE.getVendorId());
        Assert.assertEquals(0, NxmType.NXM_OF_ARP_OP.getVendorId());
        Assert.assertEquals(0, NxmType.NXM_OF_ARP_SPA.getVendorId());
        Assert.assertEquals(0, NxmType.NXM_OF_ARP_TPA.getVendorId());
        Assert.assertEquals(1, NxmType.NXM_NX_TUN_ID.getVendorId());
    }

    @Test
    public void testFields() throws Exception {
        Assert.assertEquals(0, NxmType.NXM_OF_IN_PORT.getField());
        Assert.assertEquals(1, NxmType.NXM_OF_ETH_DST.getField());
        Assert.assertEquals(2, NxmType.NXM_OF_ETH_SRC.getField());
        Assert.assertEquals(3, NxmType.NXM_OF_ETH_TYPE.getField());
        Assert.assertEquals(4, NxmType.NXM_OF_VLAN_TCI.getField());
        Assert.assertEquals(5, NxmType.NXM_OF_IP_TOS.getField());
        Assert.assertEquals(6, NxmType.NXM_OF_IP_PROTO.getField());
        Assert.assertEquals(7, NxmType.NXM_OF_IP_SRC.getField());
        Assert.assertEquals(8, NxmType.NXM_OF_IP_DST.getField());
        Assert.assertEquals(9, NxmType.NXM_OF_TCP_SRC.getField());
        Assert.assertEquals(10, NxmType.NXM_OF_TCP_DST.getField());
        Assert.assertEquals(11, NxmType.NXM_OF_UDP_SRC.getField());
        Assert.assertEquals(12, NxmType.NXM_OF_UDP_DST.getField());
        Assert.assertEquals(13, NxmType.NXM_OF_ICMP_TYPE.getField());
        Assert.assertEquals(14, NxmType.NXM_OF_ICMP_CODE.getField());
        Assert.assertEquals(15, NxmType.NXM_OF_ARP_OP.getField());
        Assert.assertEquals(16, NxmType.NXM_OF_ARP_SPA.getField());
        Assert.assertEquals(17, NxmType.NXM_OF_ARP_TPA.getField());
        Assert.assertEquals(16, NxmType.NXM_NX_TUN_ID.getField());
    }

    @Test
    public void testType() throws Exception {
        Assert.assertEquals(0, NxmType.NXM_OF_IN_PORT.getVal());
        Assert.assertEquals(1, NxmType.NXM_OF_ETH_DST.getVal());
        Assert.assertEquals(2, NxmType.NXM_OF_ETH_SRC.getVal());
        Assert.assertEquals(3, NxmType.NXM_OF_ETH_TYPE.getVal());
        Assert.assertEquals(4, NxmType.NXM_OF_VLAN_TCI.getVal());
        Assert.assertEquals(5, NxmType.NXM_OF_IP_TOS.getVal());
        Assert.assertEquals(6, NxmType.NXM_OF_IP_PROTO.getVal());
        Assert.assertEquals(7, NxmType.NXM_OF_IP_SRC.getVal());
        Assert.assertEquals(8, NxmType.NXM_OF_IP_DST.getVal());
        Assert.assertEquals(9, NxmType.NXM_OF_TCP_SRC.getVal());
        Assert.assertEquals(10, NxmType.NXM_OF_TCP_DST.getVal());
        Assert.assertEquals(11, NxmType.NXM_OF_UDP_SRC.getVal());
        Assert.assertEquals(12, NxmType.NXM_OF_UDP_DST.getVal());
        Assert.assertEquals(13, NxmType.NXM_OF_ICMP_TYPE.getVal());
        Assert.assertEquals(14, NxmType.NXM_OF_ICMP_CODE.getVal());
        Assert.assertEquals(15, NxmType.NXM_OF_ARP_OP.getVal());
        Assert.assertEquals(16, NxmType.NXM_OF_ARP_SPA.getVal());
        Assert.assertEquals(17, NxmType.NXM_OF_ARP_TPA.getVal());
        int val = makeType(0x0001, 16);
        Assert.assertEquals(val, NxmType.NXM_NX_TUN_ID.getVal());
    }

    @Test
    public void testLen() throws Exception {
        Assert.assertEquals(2, NxmType.NXM_OF_IN_PORT.getLen());
        Assert.assertEquals(6, NxmType.NXM_OF_ETH_DST.getLen());
        Assert.assertEquals(6, NxmType.NXM_OF_ETH_SRC.getLen());
        Assert.assertEquals(2, NxmType.NXM_OF_ETH_TYPE.getLen());
        Assert.assertEquals(2, NxmType.NXM_OF_VLAN_TCI.getLen());
        Assert.assertEquals(1, NxmType.NXM_OF_IP_TOS.getLen());
        Assert.assertEquals(1, NxmType.NXM_OF_IP_PROTO.getLen());
        Assert.assertEquals(4, NxmType.NXM_OF_IP_SRC.getLen());
        Assert.assertEquals(4, NxmType.NXM_OF_IP_DST.getLen());
        Assert.assertEquals(2, NxmType.NXM_OF_TCP_SRC.getLen());
        Assert.assertEquals(2, NxmType.NXM_OF_TCP_DST.getLen());
        Assert.assertEquals(2, NxmType.NXM_OF_UDP_SRC.getLen());
        Assert.assertEquals(2, NxmType.NXM_OF_UDP_DST.getLen());
        Assert.assertEquals(1, NxmType.NXM_OF_ICMP_TYPE.getLen());
        Assert.assertEquals(1, NxmType.NXM_OF_ICMP_CODE.getLen());
        Assert.assertEquals(2, NxmType.NXM_OF_ARP_OP.getLen());
        Assert.assertEquals(4, NxmType.NXM_OF_ARP_SPA.getLen());
        Assert.assertEquals(4, NxmType.NXM_OF_ARP_TPA.getLen());
        Assert.assertEquals(8, NxmType.NXM_NX_TUN_ID.getLen());
    }

    @Test
    public void testGet() throws Exception {
        int val = makeType(0x0000, 0);
        Assert.assertEquals(NxmType.NXM_OF_IN_PORT, NxmType.get(val));
        val = makeType(0x0000, 1);
        Assert.assertEquals(NxmType.NXM_OF_ETH_DST, NxmType.get(val));
        val = makeType(0x0000, 2);
        Assert.assertEquals(NxmType.NXM_OF_ETH_SRC, NxmType.get(val));
        val = makeType(0x0000, 3);
        Assert.assertEquals(NxmType.NXM_OF_ETH_TYPE, NxmType.get(val));
        val = makeType(0x0000, 4);
        Assert.assertEquals(NxmType.NXM_OF_VLAN_TCI, NxmType.get(val));
        val = makeType(0x0000, 5);
        Assert.assertEquals(NxmType.NXM_OF_IP_TOS, NxmType.get(val));
        val = makeType(0x0000, 6);
        Assert.assertEquals(NxmType.NXM_OF_IP_PROTO, NxmType.get(val));
        val = makeType(0x0000, 7);
        Assert.assertEquals(NxmType.NXM_OF_IP_SRC, NxmType.get(val));
        val = makeType(0x0000, 8);
        Assert.assertEquals(NxmType.NXM_OF_IP_DST, NxmType.get(val));
        val = makeType(0x0000, 9);
        Assert.assertEquals(NxmType.NXM_OF_TCP_SRC, NxmType.get(val));
        val = makeType(0x0000, 10);
        Assert.assertEquals(NxmType.NXM_OF_TCP_DST, NxmType.get(val));
        val = makeType(0x0000, 11);
        Assert.assertEquals(NxmType.NXM_OF_UDP_SRC, NxmType.get(val));
        val = makeType(0x0000, 12);
        Assert.assertEquals(NxmType.NXM_OF_UDP_DST, NxmType.get(val));
        val = makeType(0x0000, 13);
        Assert.assertEquals(NxmType.NXM_OF_ICMP_TYPE, NxmType.get(val));
        val = makeType(0x0000, 14);
        Assert.assertEquals(NxmType.NXM_OF_ICMP_CODE, NxmType.get(val));
        val = makeType(0x0000, 15);
        Assert.assertEquals(NxmType.NXM_OF_ARP_OP, NxmType.get(val));
        val = makeType(0x0000, 16);
        Assert.assertEquals(NxmType.NXM_OF_ARP_SPA, NxmType.get(val));
        val = makeType(0x0000, 17);
        Assert.assertEquals(NxmType.NXM_OF_ARP_TPA, NxmType.get(val));
        val = makeType(0x0001, 16);
        Assert.assertEquals(NxmType.NXM_NX_TUN_ID, NxmType.get(val));
    }

    @Test
    public void testMakeHeader() throws Exception {
        int header = makeHeader(0x0000, 0, 0, 2);
        Assert.assertEquals(header, NxmType.NXM_OF_IN_PORT.makeHeader(true));
        header = makeHeader(0x0000, 1, 1, 6);
        Assert.assertEquals(header, NxmType.NXM_OF_ETH_DST.makeHeader(true));
        // NXM_OF_ETH_SRC is not maskable
        header = makeHeader(0x0000, 2, 0, 6);
        Assert.assertEquals(header, NxmType.NXM_OF_ETH_SRC.makeHeader(true));
        header = makeHeader(0x0000, 3, 0, 2);
        Assert.assertEquals(header, NxmType.NXM_OF_ETH_TYPE.makeHeader(true));
        header = makeHeader(0x0000, 4, 1, 2);
        Assert.assertEquals(header, NxmType.NXM_OF_VLAN_TCI.makeHeader(true));
        header = makeHeader(0x0000, 5, 0, 1);
        Assert.assertEquals(header, NxmType.NXM_OF_IP_TOS.makeHeader(true));
        header = makeHeader(0x0000, 6, 0, 1);
        Assert.assertEquals(header, NxmType.NXM_OF_IP_PROTO.makeHeader(true));
        header = makeHeader(0x0000, 7, 1, 4);
        Assert.assertEquals(header, NxmType.NXM_OF_IP_SRC.makeHeader(true));
        header = makeHeader(0x0000, 8, 1, 4);
        Assert.assertEquals(header, NxmType.NXM_OF_IP_DST.makeHeader(true));
        header = makeHeader(0x0000, 9, 0, 2);
        Assert.assertEquals(header, NxmType.NXM_OF_TCP_SRC.makeHeader(true));
        header = makeHeader(0x0000, 10, 0, 2);
        Assert.assertEquals(header, NxmType.NXM_OF_TCP_DST.makeHeader(true));
        header = makeHeader(0x0000, 11, 0, 2);
        Assert.assertEquals(header, NxmType.NXM_OF_UDP_SRC.makeHeader(true));
        header = makeHeader(0x0000, 12, 0, 2);
        Assert.assertEquals(header, NxmType.NXM_OF_UDP_DST.makeHeader(true));
        header = makeHeader(0x0000, 13, 0, 1);
        Assert.assertEquals(header, NxmType.NXM_OF_ICMP_TYPE.makeHeader(true));
        header = makeHeader(0x0000, 14, 0, 1);
        Assert.assertEquals(header, NxmType.NXM_OF_ICMP_CODE.makeHeader(true));
        header = makeHeader(0x0000, 15, 0, 2);
        Assert.assertEquals(header, NxmType.NXM_OF_ARP_OP.makeHeader(true));
        header = makeHeader(0x0000, 16, 1, 4);
        Assert.assertEquals(header, NxmType.NXM_OF_ARP_SPA.makeHeader(true));
        header = makeHeader(0x0000, 17, 1, 4);
        Assert.assertEquals(header, NxmType.NXM_OF_ARP_TPA.makeHeader(true));
        header = makeHeader(0x0001, 16, 1, 8);
        Assert.assertEquals(header, NxmType.NXM_NX_TUN_ID.makeHeader(true));

        header = makeHeader(0x0000, 0, 0, 2);
        Assert.assertEquals(header, NxmType.NXM_OF_IN_PORT.makeHeader(false));
        header = makeHeader(0x0000, 1, 0, 6);
        Assert.assertEquals(header, NxmType.NXM_OF_ETH_DST.makeHeader(false));
        header = makeHeader(0x0000, 2, 0, 6);
        Assert.assertEquals(header, NxmType.NXM_OF_ETH_SRC.makeHeader(false));
        header = makeHeader(0x0000, 3, 0, 2);
        Assert.assertEquals(header, NxmType.NXM_OF_ETH_TYPE.makeHeader(false));
        header = makeHeader(0x0000, 4, 0, 2);
        Assert.assertEquals(header, NxmType.NXM_OF_VLAN_TCI.makeHeader(false));
        header = makeHeader(0x0000, 5, 0, 1);
        Assert.assertEquals(header, NxmType.NXM_OF_IP_TOS.makeHeader(false));
        header = makeHeader(0x0000, 6, 0, 1);
        Assert.assertEquals(header, NxmType.NXM_OF_IP_PROTO.makeHeader(false));
        header = makeHeader(0x0000, 7, 0, 4);
        Assert.assertEquals(header, NxmType.NXM_OF_IP_SRC.makeHeader(false));
        header = makeHeader(0x0000, 8, 0, 4);
        Assert.assertEquals(header, NxmType.NXM_OF_IP_DST.makeHeader(false));
        header = makeHeader(0x0000, 9, 0, 2);
        Assert.assertEquals(header, NxmType.NXM_OF_TCP_SRC.makeHeader(false));
        header = makeHeader(0x0000, 10, 0, 2);
        Assert.assertEquals(header, NxmType.NXM_OF_TCP_DST.makeHeader(false));
        header = makeHeader(0x0000, 11, 0, 2);
        Assert.assertEquals(header, NxmType.NXM_OF_UDP_SRC.makeHeader(false));
        header = makeHeader(0x0000, 12, 0, 2);
        Assert.assertEquals(header, NxmType.NXM_OF_UDP_DST.makeHeader(false));
        header = makeHeader(0x0000, 13, 0, 1);
        Assert.assertEquals(header, NxmType.NXM_OF_ICMP_TYPE.makeHeader(false));
        header = makeHeader(0x0000, 14, 0, 1);
        Assert.assertEquals(header, NxmType.NXM_OF_ICMP_CODE.makeHeader(false));
        header = makeHeader(0x0000, 15, 0, 2);
        Assert.assertEquals(header, NxmType.NXM_OF_ARP_OP.makeHeader(false));
        header = makeHeader(0x0000, 16, 0, 4);
        Assert.assertEquals(header, NxmType.NXM_OF_ARP_SPA.makeHeader(false));
        header = makeHeader(0x0000, 17, 0, 4);
        Assert.assertEquals(header, NxmType.NXM_OF_ARP_TPA.makeHeader(false));
        header = makeHeader(0x0001, 16, 0, 8);
        Assert.assertEquals(header, NxmType.NXM_NX_TUN_ID.makeHeader(false));
    }
}
