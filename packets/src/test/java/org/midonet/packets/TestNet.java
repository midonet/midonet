// Copyright 2012 Midokura Inc.

package org.midonet.packets;

import org.junit.Assert;
import org.junit.Test;
import java.net.InetAddress;
import java.net.UnknownHostException;

public class TestNet {

    @Test
    public void testConvertIntAddressToString() {
        Assert.assertEquals("192.168.1.2",
                Net.convertIntAddressToString(-1062731518));
    }

    @Test
    public void testConvertStringAddressToInt() {
        Assert.assertEquals(0xc0a80102,
                Net.convertStringAddressToInt("192.168.1.2"));
    }

    @Test
    public void testConvertAddress() {
        Assert.assertEquals("192.168.1.2",
            Net.convertIntAddressToString(
                    Net.convertStringAddressToInt("192.168.1.2")));
    }
}
