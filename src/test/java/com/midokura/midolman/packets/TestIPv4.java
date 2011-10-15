package com.midokura.midolman.packets;

import java.util.Random;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class TestIPv4 {

    byte[] data;
    
    @Before
    public void init() {
        data = new byte[] {
            (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00,
            (byte)0xb2, (byte)0x78, (byte)0x00, (byte)0x01,
            (byte)0xf8, (byte)0x59, (byte)0x98, (byte)0x4e,
            (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00,
            (byte)0xc6, (byte)0xec, (byte)0x0b, (byte)0x00,
            (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00,
            (byte)0x10, (byte)0x11, (byte)0x12, (byte)0x13,
            (byte)0x14, (byte)0x15, (byte)0x16, (byte)0x17,
            (byte)0x18, (byte)0x19, (byte)0x1a, (byte)0x1b,
            (byte)0x1c, (byte)0x1d, (byte)0x1e, (byte)0x1f,
            (byte)0x20, (byte)0x21, (byte)0x22, (byte)0x23,
            (byte)0x24, (byte)0x25, (byte)0x26, (byte)0x27,
            (byte)0x28, (byte)0x29, (byte)0x2a, (byte)0x2b,
            (byte)0x2c, (byte)0x2d, (byte)0x2e, (byte)0x2f,
            (byte)0x30, (byte)0x31, (byte)0x32, (byte)0x33,
            (byte)0x34, (byte)0x35, (byte)0x36, (byte)0x37
        };
    }

    @Test
    public void testZeroArrays() {
        byte[] bytes = new byte[100];
        short expCksum = (short) 0xffff;
        short cksum = IPv4.computeChecksum(bytes, 0, 0, 0);
        Assert.assertEquals(expCksum, cksum);
        cksum = IPv4.computeChecksum(bytes, 0, 100, 100);
        Assert.assertEquals(expCksum, cksum);
        cksum = IPv4.computeChecksum(bytes, 1, 99, 100);
        Assert.assertEquals(expCksum, cksum);
    }

    @Test
    public void testRealData() {
        short cksum = IPv4.computeChecksum(data, 0, data.length, 0);
        data[0] = (byte) (cksum >> 8);
        data[1] = (byte) cksum;
        // Verify that the checksum field is ignored by getting the same cksum.
        Assert.assertEquals(cksum,
                IPv4.computeChecksum(data, 0, data.length, 0));
        // Now verify that when we don't ignore the cksum, we get zero.
        Assert.assertEquals(0, IPv4.computeChecksum(data, 0, data.length, -2));

        // Repeat with a different subset of the array (and odd length).
        cksum = IPv4.computeChecksum(data, 0, 45, 0);
        // Set the checksum field.
        data[0] = (byte) (cksum >> 8);
        data[1] = (byte) cksum;
        // Now verify that when we don't ignore the cksum, we get zero.
        Assert.assertEquals(0, IPv4.computeChecksum(data, 0, 45, -2));
    }

    @Test
    public void testRandomArrays() {
        Random rand = new Random(12345);
        for (int i = 0; i < 10; i++) {
            // Generate a random length between 100 and 1000
            int length = rand.nextInt(900) + 100;
            byte[] data = new byte[length];
            rand.nextBytes(data);
            short cksum = IPv4.computeChecksum(data, 0, data.length, 0);
            data[0] = (byte) (cksum >> 8);
            data[1] = (byte) cksum;
            // Verify that if we don't ignore the cksum, we get zero.
            Assert.assertEquals(0,
                    IPv4.computeChecksum(data, 0, data.length, -2));
        }
    }
}
