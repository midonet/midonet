package com.midokura.midolman.rules;

import java.util.Random;
import java.util.UUID;

import org.junit.Assert;

import org.junit.BeforeClass;
import org.junit.Test;

import com.midokura.midolman.openflow.MidoMatch;

public class TestCondition {

    static MidoMatch pktMatch;
    static Random rand;

    @BeforeClass
    public static void setup() {
        pktMatch = new MidoMatch();
        pktMatch.setInputPort((short)5);
        pktMatch.setDataLayerSource("02:11:33:00:11:01");
        pktMatch.setDataLayerDestination("02:11:aa:ee:22:05");
        pktMatch.setNetworkSource(0x0a001406, 32);
        pktMatch.setNetworkDestination(0x0a001406, 32);
        pktMatch.setNetworkProtocol((byte)8);
        pktMatch.setTransportSource((short)4321);
        pktMatch.setTransportDestination((short)1234);
        rand = new Random();
    }

    @Test
    public void testConjunctionInv() {
        Condition cond = new Condition();
        UUID inPort = new UUID(rand.nextLong(), rand.nextLong());
        // This condition should match all packets.
        Assert.assertTrue(cond.matches(inPort, null, pktMatch));
        cond.conjunctionInv = true;
        Assert.assertFalse(cond.matches(inPort, null, pktMatch));
    }

}
