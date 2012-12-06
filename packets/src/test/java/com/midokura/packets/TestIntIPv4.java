/*
 * Copyright 2012 Midokura Europe SARL
 */

package com.midokura.packets;

import static org.junit.Assert.assertEquals;
import org.junit.Test;


public class TestIntIPv4 {

    @Test
    public void testStringConversion() {
        String ipStr = "10.0.10.5";
        IntIPv4 ip = IntIPv4.fromString(ipStr);
        assertEquals("IntIPv4.fromString(ipStr).toString() should equal ipStr",
                ipStr, ip.toString());

        ipStr = "10.1.2.0";
        ip =  IntIPv4.fromString(ipStr, 24);
        assertEquals("IntIPv4.fromString(ipStr, length).toUnicastString() " +
                "should equal ipStr", ipStr, ip.toUnicastString());
        assertEquals("IntIPv4.fromString(ipStr, length).getMaskLength() " +
                "should equal length", 24, ip.getMaskLength());

        ip = new IntIPv4(Net.convertStringAddressToInt("192.168.10.0"), 24);
        assertEquals("IntIPv4.fromString(ipv4.toString()) should equal ipv4",
                ip, IntIPv4.fromString(ip.toString()));

        ip = new IntIPv4(Net.convertStringAddressToInt("192.168.10.10"), 32);
        assertEquals("IntIPv4.fromString(ipv4.toString()) should equal ipv4",
                ip, IntIPv4.fromString(ip.toString()));
    }
}
