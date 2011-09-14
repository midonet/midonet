package com.midokura.midolman.util;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

public class TestNet {
    @Test
    public void testConvertAddressToString() {
        Assert.assertEquals("192.168.1.2", Net.convertAddressToString(-1062731518));
    }
    @Test
    public void testConvertAddressToInt() {
        Assert.assertEquals(0xc0a80102, Net.convertAddressToInt("192.168.1.2"));
    }
    @Test
    public void testConvertAddress() {
        Assert.assertEquals("192.168.1.2",
            Net.convertAddressToString(Net.convertAddressToInt("192.168.1.2")));
    }
}