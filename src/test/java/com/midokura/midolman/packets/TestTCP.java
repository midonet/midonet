package com.midokura.midolman.packets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Random;

import org.junit.Test;

import scala.actors.threadpool.Arrays;

public class TestTCP {

    @Test
    public void test() {
        TCP tcp = new TCP();
        tcp.setSourcePort((short) 4321);
        tcp.setDestinationPort((short) 1234);
        int seqNo = 1222333444;
        tcp.setSeqNo(seqNo);
        tcp.setAckNo(seqNo-100);
        tcp.setFlags((short)0xf000); // Sets the data offset to 15 words.
        tcp.setWindowSize((short)4000);
        // Don't set checksum or urgent. Cksum computed during serialization.
        Random rnd = new Random();
        byte[] options = new byte[40];
        rnd.nextBytes(options);
        tcp.setOptions(options);
        byte[] payload = new byte[1000];
        rnd.nextBytes(payload);
        tcp.setPayload(new Data(payload));
        byte[] segmentBytes = tcp.serialize();
        assertEquals(segmentBytes.length, payload.length + 60);
        // Deserialize the whole segment.
        TCP tcp2 = new TCP();
        tcp2.deserialize(segmentBytes, 0, segmentBytes.length);
        assertEquals(tcp, tcp2);
        // Deserialize the segment truncated after the options - 150 bytes.
        byte[] truncated = Arrays.copyOf(segmentBytes, 150);
        tcp2 = new TCP();
        tcp2.deserialize(truncated, 0, truncated.length);
        tcp.setPayload(new Data(Arrays.copyOf(payload, 90)));
        assertEquals(tcp, tcp2);
        // Deserialize the segment truncated before the options - 50 bytes.
        truncated = Arrays.copyOf(segmentBytes, 50);
        tcp2 = new TCP();
        tcp2.deserialize(truncated, 0, truncated.length);
        tcp.setPayload(null);
        tcp.setOptions(Arrays.copyOf(options, 30));
        assertEquals(tcp, tcp2);
    }

}
