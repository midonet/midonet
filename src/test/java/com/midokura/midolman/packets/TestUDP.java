/*
 * Copyright 2011 Midokura KK
 */

package com.midokura.midolman.packets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Random;

import org.junit.Test;

import scala.actors.threadpool.Arrays;

public class TestUDP {

    @Test
    public void testLargeDatagramSerialization() {
        UDP udp = new UDP();
        udp.setDestinationPort((short) 1234);
        udp.setSourcePort((short) 4321);
        Random rnd = new Random();
        byte[] payload = new byte[50000];
        rnd.nextBytes(payload);
        udp.setPayload(new Data(payload));
        byte[] dgramBytes = udp.serialize();
        // Deserialize the whole datagram.
        udp = new UDP();
        udp.deserialize(dgramBytes, 0, dgramBytes.length);
        assertEquals(udp.getLength() & 0xffff, payload.length + 8);
        Data pay2 = Data.class.cast(udp.getPayload());
        assertTrue(Arrays.equals(payload, pay2.getData()));
        // Deserialize the truncated datagram.
        byte[] truncated = Arrays.copyOf(dgramBytes, 150);
        udp = new UDP();
        udp.deserialize(truncated, 0, truncated.length);
        assertEquals(udp.getLength() & 0xffff, payload.length + 8);
        pay2 = Data.class.cast(udp.getPayload());
        assertTrue(Arrays.equals(Arrays.copyOf(payload, truncated.length - 8),
                pay2.getData()));
    }

}
