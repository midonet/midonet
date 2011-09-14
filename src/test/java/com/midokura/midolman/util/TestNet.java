package com.midokura.midolman.util;

import org.junit.Assert;
import org.junit.Test;
import java.net.InetAddress;
import java.net.UnknownHostException;

public class TestNet {

    @Test
    public void testConvertInetAddressToInt() throws UnknownHostException {
        Assert.assertEquals(0xc0a801f2, 
	    Net.convertInetAddressToInt(InetAddress.getByAddress(
		new byte[] { (byte)0xc0, (byte)0xa8, (byte)0x01, (byte)0xf2 }
        )));
    }

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
