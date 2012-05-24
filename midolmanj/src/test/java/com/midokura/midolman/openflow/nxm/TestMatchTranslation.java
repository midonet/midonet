/*
 * Copyright 2012 Midokura Europe SARL
 */

package com.midokura.midolman.openflow.nxm;

import org.junit.Test;
import org.openflow.protocol.OFMatch;


import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;

public class TestMatchTranslation {

    @Test
    public void testARP() throws NxmIOException {
        OFMatch match = Match.arp();
        NxMatch nxm = MatchTranslation.toNxMatch(match, 0, 0);
        assertThat("toOFMatch(toNxMatch(arpOFMatch) should equal arpOFMatch",
                match, equalTo(MatchTranslation.toOFMatch(nxm)));
        // Exercise the toString method.
        nxm.toString();
    }

    @Test
    public void testARPinVLAN() throws NxmIOException {
        OFMatch match = Match.arpInVlan();
        NxMatch nxm = MatchTranslation.toNxMatch(match, 0, 0);
        OFMatch match1 = MatchTranslation.toOFMatch(nxm);
        assertThat("toOFMatch(toNxMatch(arpInVlanMatch) should equal " +
                "arpInVlanMatch", match, equalTo(match1));
        // Exercise the toString method.
        nxm.toString();
    }

    @Test
    public void testTCP() throws NxmIOException {
        OFMatch match = Match.tcp();
        NxMatch nxm = MatchTranslation.toNxMatch(match, 0, 0);
        assertThat("toOFMatch(toNxMatch(tcpOFMatch) should equal tcpOFMatch",
                match, equalTo(MatchTranslation.toOFMatch(nxm)));
        // Exercise the toString method.
        nxm.toString();
    }

    @Test
    public void testUDP() throws NxmIOException {
        OFMatch match = Match.udp();
        NxMatch nxm = MatchTranslation.toNxMatch(match, 0, 0);
        assertThat("toOFMatch(toNxMatch(udpOFMatch) should equal udpOFMatch",
                match, equalTo(MatchTranslation.toOFMatch(nxm)));
        // Exercise the toString method.
        nxm.toString();
    }

    @Test
    public void testICMP() throws NxmIOException {
        OFMatch match = Match.icmp();
        NxMatch nxm = MatchTranslation.toNxMatch(match, 0, 0);
        assertThat("toOFMatch(toNxMatch(icmpOFMatch) should equal icmpOFMatch",
                match, equalTo(MatchTranslation.toOFMatch(nxm)));
        // Exercise the toString method.
        nxm.toString();
    }

    @Test
    public void testIPv6() throws NxmIOException {
        OFMatch match = Match.ipv6();
        NxMatch nxm = MatchTranslation.toNxMatch(match, 0, 0);
        assertThat("toOFMatch(toNxMatch(ipv6OFMatch) should equal ipv6OFMatch",
                match, equalTo(MatchTranslation.toOFMatch(nxm)));
        // Exercise the toString method.
        nxm.toString();
    }
}
