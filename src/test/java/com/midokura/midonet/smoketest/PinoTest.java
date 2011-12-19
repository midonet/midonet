package com.midokura.midonet.smoketest;

import com.midokura.midolman.packets.IntIPv4;
import com.midokura.midonet.smoketest.topology.TapPort;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

public class PinoTest {

    @Test
    public void test() {
        TapPort p = new TapPort(null, null, "pinoTap");
        PacketHelper helper = new PacketHelper(p.getInnerMAC(),
                IntIPv4.fromString("10.0.0.11"), p.getOuterMAC(),
                IntIPv4.fromString("10.0.0.13"));
        byte[] request = helper.makeIcmpEchoRequest(
                IntIPv4.fromString("10.0.0.20"));
        assertTrue(p.send(request));
    }
}
