/*
 * Copyright 2014 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.midonet.midolman.rules;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import org.codehaus.jackson.JsonFactory;
import org.codehaus.jackson.JsonGenerator;
import org.codehaus.jackson.JsonParser;
import org.codehaus.jackson.annotate.JsonAutoDetect.Visibility;
import org.codehaus.jackson.map.ObjectMapper;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.midonet.midolman.simulation.IPAddrGroup;
import org.midonet.midolman.simulation.PacketContext;
import org.midonet.odp.FlowMatch;
import org.midonet.packets.*;
import org.midonet.util.Range;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TestCondition {

    private FlowMatch pktMatch;
    static Random rand;
    private static ObjectMapper objectMapper = new ObjectMapper();
    private static JsonFactory jsonFactory = new JsonFactory(objectMapper);
    private PacketContext pktCtx;

    static {
        objectMapper.setVisibilityChecker(objectMapper.getVisibilityChecker()
                                              .withFieldVisibility(
                                                  Visibility.ANY));
        objectMapper.enableDefaultTyping(ObjectMapper.DefaultTyping.NON_FINAL);
    }

    private final IPAddr dstIpAddr = new IPv4Addr(0x0a000b22);
    private final IPAddr srcIpAddr = new IPv4Addr(0x0a001406);

    @Before
    public void setUp() {
        pktMatch = new FlowMatch();
        pktMatch.setInputPortNumber((short) 5);
        pktMatch.setEthSrc("02:11:33:00:11:01");
        pktMatch.setEthDst("02:11:aa:ee:22:05");
        pktMatch.setEtherType(IPv4.ETHERTYPE);
        pktMatch.setNetworkSrc(srcIpAddr);
        pktMatch.setNetworkDst(dstIpAddr);
        pktMatch.setNetworkProto((byte) 6);
        pktMatch.setNetworkTOS((byte) 34);
        pktMatch.setSrcPort(4321);
        pktMatch.setDstPort(1234);
        rand = new Random();
        pktCtx = new PacketContext(1, null, pktMatch, null);
    }

    @Test
    public void testConjunctionInv() {
        Condition cond = new Condition();
        // This condition should match all packets.
        pktCtx.inPortId_$eq(new UUID(rand.nextLong(), rand.nextLong()));
        Assert.assertTrue(cond.matches(pktCtx, false));
        cond.conjunctionInv = true;
        Assert.assertFalse(cond.matches(pktCtx, false));
    }

    @Test
    public void testInPortIds() {
        Condition cond = new Condition();
        UUID inPort = new UUID(rand.nextLong(), rand.nextLong());
        cond.inPortIds = new HashSet<>();
        cond.inPortIds.add(new UUID(rand.nextLong(), rand.nextLong()));
        cond.inPortIds.add(new UUID(rand.nextLong(), rand.nextLong()));
        // The condition should not match the packet.
        Assert.assertFalse(cond.matches(pktCtx, false));
        pktCtx.inPortId_$eq(inPort);
        Assert.assertFalse(cond.matches(pktCtx, false));
        // Verify that inPortInv causes a match.
        cond.inPortInv = true;
        Assert.assertTrue(cond.matches(pktCtx, false));
        pktCtx.inPortId_$eq(null);
        Assert.assertTrue(cond.matches(pktCtx, false));
        // Now add inPort to the condition - it stops matching due to invert.
        cond.inPortIds.add(inPort);
        pktCtx.inPortId_$eq(inPort);
        Assert.assertFalse(cond.matches(pktCtx, false));
        cond.inPortInv = false;
        Assert.assertTrue(cond.matches(pktCtx, false));
    }

    @Test
    public void testOutPortIds() {
        Condition cond = new Condition();
        UUID inPort = new UUID(rand.nextLong(), rand.nextLong());
        UUID outPort = new UUID(rand.nextLong(), rand.nextLong());
        pktCtx.inPortId_$eq(inPort);
        cond.outPortIds = new HashSet<>();
        cond.outPortIds.add(new UUID(rand.nextLong(), rand.nextLong()));
        cond.outPortIds.add(new UUID(rand.nextLong(), rand.nextLong()));
        // The condition should not match the packet.
        Assert.assertFalse(cond.matches(pktCtx, false));
        Assert.assertFalse(cond.matches(pktCtx, true));
        pktCtx.outPortId_$eq(outPort);
        Assert.assertFalse(cond.matches(pktCtx, true));
        Assert.assertFalse(cond.matches(pktCtx, false));
        // Verify that outPortInv causes a match.
        cond.outPortInv = true;
        Assert.assertTrue(cond.matches(pktCtx, false));
        Assert.assertTrue(cond.matches(pktCtx, true));
        pktCtx.outPortId_$eq(null);
        Assert.assertTrue(cond.matches(pktCtx, false));
        Assert.assertTrue(cond.matches(pktCtx, true));
        // Now add outPort to the condition - it stops matching due to invert
        // on forwarding elements - for both port filters and others.
        cond.outPortIds.add(outPort);
        pktCtx.outPortId_$eq(outPort);
        Assert.assertFalse(cond.matches(pktCtx, false));
        Assert.assertFalse(cond.matches(pktCtx, true));
        cond.outPortInv = false;
        Assert.assertTrue(cond.matches(pktCtx, false));
        Assert.assertTrue(cond.matches(pktCtx, true));
    }

    @Test
    public void testDlSrc() {
        Condition cond = new Condition();

        // InvDlSrc shouldn't matter when ethSrc is null.
        cond.invDlSrc = true;
        assertTrue(cond.matches(pktCtx, false));

        cond.ethSrc = pktMatch.getEthSrc();
        assertFalse(cond.matches(pktCtx, false));

        cond.invDlSrc = false;
        assertTrue(cond.matches(pktCtx, false));

        cond.ethSrc = MAC.random();
        assertFalse(cond.matches(pktCtx, false));

        cond.invDlSrc = true;
        assertTrue(cond.matches(pktCtx, false));
    }

    @Test
    public void testDlDst() {
        Condition cond = new Condition();

        // InvDlDst shouldn't matter when ethDst is null.
        cond.invDlDst = true;
        assertTrue(cond.matches(pktCtx, false));

        cond.ethDst = pktMatch.getEthDst();
        assertFalse(cond.matches(pktCtx, false));

        cond.invDlDst = false;
        assertTrue(cond.matches(pktCtx, false));

        cond.ethDst = MAC.random();
        assertFalse(cond.matches(pktCtx, false));

        cond.invDlDst = true;
        assertTrue(cond.matches(pktCtx, false));
    }

    @Test
    public void testDlSrcMasking() {
        Condition cond = new Condition();

        // Everything should match with zero mask.
        cond.ethSrcMask = 0L;
        cond.ethSrc = MAC.random();
        cond.invDlSrc = true;
        assertFalse(cond.matches(pktCtx, false));
        cond.invDlSrc = false;
        assertTrue(cond.matches(pktCtx, false));

        // Ignore lower 32 bits.
        cond.ethSrcMask = 0xffffL << 32;

        // Flip lower 32 bits and match should still succeed.
        long macLong = pktMatch.getEthSrc().asLong();
        cond.ethSrc = new MAC(macLong ^ 0xffffffffL);
        cond.invDlSrc = true;
        assertFalse(cond.matches(pktCtx, false));
        cond.invDlSrc = false;
        assertTrue(cond.matches(pktCtx, false));

        // Flip one more bit and match should fail.
        cond.ethSrc = new MAC(macLong ^ 0x1ffffffffL);
        cond.invDlSrc = true;
        assertTrue(cond.matches(pktCtx, false));
        cond.invDlSrc = false;
        assertFalse(cond.matches(pktCtx, false));
    }

    @Test
    public void testDlDstMasking() {
        Condition cond = new Condition();

        // Everything should match with zero mask.
        cond.dlDstMask = 0L;
        cond.ethDst = MAC.random();
        cond.invDlDst = true;
        assertFalse(cond.matches(pktCtx, false));
        cond.invDlDst = false;
        assertTrue(cond.matches(pktCtx, false));

        // Ignore lower 32 bits.
        cond.dlDstMask = 0xffffL << 32;

        // Flip lower 32 bits and match should still succeed.
        long macLong = pktMatch.getEthDst().asLong();
        cond.ethDst = new MAC(macLong ^ 0xffffffffL);
        cond.invDlDst = true;
        assertFalse(cond.matches(pktCtx, false));
        cond.invDlDst = false;
        assertTrue(cond.matches(pktCtx, false));


        // Flip one more bit and match should fail.
        cond.ethDst = new MAC(macLong ^ 0x1ffffffffL);
        cond.invDlDst = true;
        assertTrue(cond.matches(pktCtx, false));
        cond.invDlDst = false;
        assertFalse(cond.matches(pktCtx, false));
    }

    @Test
    public void testNwTos() {
        Condition cond = new Condition();
        pktCtx.inPortId_$eq(new UUID(rand.nextLong(), rand.nextLong()));
        Assert.assertTrue(cond.matches(pktCtx, false));
        cond.nwTos = 5;
        Assert.assertFalse(cond.matches(pktCtx, false));
        cond.nwTosInv = true;
        Assert.assertTrue(cond.matches(pktCtx, false));
        cond.nwTosInv = false;
        cond.nwTos = 34;
        Assert.assertTrue(cond.matches(pktCtx, false));
        cond.nwTosInv = true;
        Assert.assertFalse(cond.matches(pktCtx, false));
    }

    @Test
    public void testNwProto() {
        Condition cond = new Condition();
        pktCtx.inPortId_$eq(UUID.randomUUID());
        Assert.assertTrue(cond.matches(pktCtx, false));
        cond.nwProto = 5;
        Assert.assertFalse(cond.matches(pktCtx, false));
        cond.nwProtoInv = true;
        Assert.assertTrue(cond.matches(pktCtx, false));
        cond.nwProtoInv = false;
        cond.nwProto = 6;
        Assert.assertTrue(cond.matches(pktCtx, false));
        cond.nwProtoInv = true;
        Assert.assertFalse(cond.matches(pktCtx, false));
    }

    @Test
    public void testNwSrc() {
        Condition cond = new Condition();
        pktCtx.inPortId_$eq(UUID.randomUUID());
        Assert.assertTrue(cond.matches(pktCtx, false));
        // Inverting nwSrc has no effect when it's wild-carded.
        cond.nwSrcInv = true;
        Assert.assertTrue(cond.matches(pktCtx, false));
        cond.nwSrcInv = false;
        // Set the nwSrcIp to something different than the packet's 0x0a001406.
        cond.nwSrcIp = new IPv4Subnet(IPv4Addr.fromString("10.0.20.3"), 0);
        // Since nwSrcLength is still 0, the condition still matches the packet.
        Assert.assertTrue(cond.matches(pktCtx, false));
        cond.nwSrcIp = new IPv4Subnet(IPv4Addr.fromString("10.0.20.3"), 32);
        Assert.assertFalse(cond.matches(pktCtx, false));
        // Now try shorter prefixes:
        cond.nwSrcIp = new IPv4Subnet(IPv4Addr.fromString("10.0.20.3"), 24);
        Assert.assertTrue(cond.matches(pktCtx, false));
        cond.nwSrcIp = new IPv4Subnet(IPv4Addr.fromString("10.0.20.3"), 16);
        Assert.assertTrue(cond.matches(pktCtx, false));
        // Now try length 0 with an ip that differs in the left-most bit.
        cond.nwSrcIp = new IPv4Subnet(IPv4Addr.fromString("250.1.1.4"), 1);
        Assert.assertFalse(cond.matches(pktCtx, false));
        cond.nwSrcIp = new IPv4Subnet(IPv4Addr.fromString("250.1.1.4"), 0);
        Assert.assertTrue(cond.matches(pktCtx, false));
        cond.nwSrcInv = true;
        Assert.assertFalse(cond.matches(pktCtx, false));
        // Now increase the maskLength. The packet doesn't match the
        // condition's srcIp, but the nwSrcInv is true.
        cond.nwSrcIp = new IPv4Subnet(IPv4Addr.fromString("250.1.1.4"), 32);
        Assert.assertTrue(cond.matches(pktCtx, false));
        // Remove the invert, set the nwSrcIp to the packet's
        cond.nwSrcInv = false;
        Assert.assertFalse(cond.matches(pktCtx, false));
        cond.nwSrcIp = new IPv4Subnet(IPv4Addr.fromString("10.0.20.6"), 32);
        Assert.assertTrue(cond.matches(pktCtx, false));
        cond.nwSrcInv = true;
        Assert.assertFalse(cond.matches(pktCtx, false));
    }

    @Test
    public void testNwDst() {
        Condition cond = new Condition();
        // Empty condition matches the packet.
        pktCtx.inPortId_$eq(UUID.randomUUID());
        Assert.assertTrue(cond.matches(pktCtx, false));
        // Inverting is ignored if the field is null.
        cond.nwDstInv = true;
        // Condition still matches the packet.
        Assert.assertTrue(cond.matches(pktCtx, false));
        cond.nwDstInv = false;
        // Set the nwDstIp to something different than the packet's 0x0a000b22.
        cond.nwDstIp = new IPv4Subnet(IPv4Addr.fromString("10.0.11.35"), 0);
        // Since nwDstLength is 0, the condition still matches the packet.
        Assert.assertTrue(cond.matches(pktCtx, false));
        // Now try inverting the result
        cond.nwDstInv = true;
        Assert.assertFalse(cond.matches(pktCtx, false));
        cond.nwDstInv = false;
        cond.nwDstIp = new IPv4Subnet(IPv4Addr.fromString("10.0.11.35"), 32);
        Assert.assertFalse(cond.matches(pktCtx, false));
        // Now try shorter prefixes:
        cond.nwDstIp = new IPv4Subnet(IPv4Addr.fromString("10.0.11.35"), 31);
        Assert.assertTrue(cond.matches(pktCtx, false));
        cond.nwDstIp = new IPv4Subnet(IPv4Addr.fromString("10.0.11.35"), 24);
        Assert.assertTrue(cond.matches(pktCtx, false));
        cond.nwDstIp = new IPv4Subnet(IPv4Addr.fromString("10.0.11.35"), 16);
        Assert.assertTrue(cond.matches(pktCtx, false));
        // Now try inverting
        cond.nwDstInv = true;
        Assert.assertFalse(cond.matches(pktCtx, false));
        cond.nwDstIp = new IPv4Subnet(IPv4Addr.fromString("10.0.11.35"), 32);
        Assert.assertTrue(cond.matches(pktCtx, false));
        // Remove the invert, set the nwDstIp to the packet's
        cond.nwDstInv = false;
        cond.nwDstIp = new IPv4Subnet(IPv4Addr.fromString("10.0.11.34"), 32);
        Assert.assertTrue(cond.matches(pktCtx, false));
        cond.nwDstInv = true;
        Assert.assertFalse(cond.matches(pktCtx, false));
    }

    @Test
    public void testTpSrc() {
        // Note tpSrc is set to 4321
        Condition cond = new Condition();
        pktCtx.inPortId_$eq(UUID.randomUUID());
        Assert.assertTrue(cond.matches(pktCtx, false));
        cond.tpSrc = new Range<>(30, Transport.MAX_PORT_NO);
        Assert.assertTrue(cond.matches(pktCtx, false));
        cond.tpSrcInv = true;
        Assert.assertFalse(cond.matches(pktCtx, false));
        cond.tpSrcInv = false;
        Assert.assertTrue(cond.matches(pktCtx, false));
        cond.tpSrc = new Range<>(4322, Transport.MAX_PORT_NO);
        Assert.assertFalse(cond.matches(pktCtx, false));
        cond.tpSrcInv = true;
        Assert.assertTrue(cond.matches(pktCtx, false));
        cond.tpSrcInv = false;
        Assert.assertFalse(cond.matches(pktCtx, false));
        cond.tpSrc = new Range<>(4321, 4321) ;
        Assert.assertTrue(cond.matches(pktCtx, false));
        cond.tpSrcInv = true;
        Assert.assertFalse(cond.matches(pktCtx, false));
        cond.tpSrcInv = false;
        cond.tpSrc = new Range<>(0, 4322);
        Assert.assertTrue(cond.matches(pktCtx, false));
    }

    @Test
    public void testTpSrc_upperPorts() {
        pktCtx.wcmatch().setSrcPort(40000);

        Condition cond = new Condition();
        pktCtx.inPortId_$eq(UUID.randomUUID());
        Assert.assertTrue(cond.matches(pktCtx, false));
        cond.tpSrc = new Range<>(30000, Transport.MAX_PORT_NO);
        Assert.assertTrue(cond.matches(pktCtx, false));
        cond.tpSrcInv = true;
        Assert.assertFalse(cond.matches(pktCtx, false));
        cond.tpSrcInv = false;
        Assert.assertTrue(cond.matches(pktCtx, false));
        cond.tpSrc = new Range<>(45000, Transport.MAX_PORT_NO);
        Assert.assertFalse(cond.matches(pktCtx, false));
        cond.tpSrcInv = true;
        Assert.assertTrue(cond.matches(pktCtx, false));
        cond.tpSrcInv = false;
        Assert.assertFalse(cond.matches(pktCtx, false));
        cond.tpSrc = new Range<>(35000, 45000);
        Assert.assertTrue(cond.matches(pktCtx, false));
        cond.tpSrcInv = true;
        Assert.assertFalse(cond.matches(pktCtx, false));
        cond.tpSrcInv = false;
        cond.tpSrc = new Range<>(0, Transport.MAX_PORT_NO);
        Assert.assertTrue(cond.matches(pktCtx, false));
    }

    @Test
    public void testTpDst() {
        // tpDst is set to 1234
        Condition cond = new Condition();
        pktCtx.inPortId_$eq(UUID.randomUUID());
        Assert.assertTrue(cond.matches(pktCtx, false));
        cond.tpDst = new Range<>(1235, Transport.MAX_PORT_NO);
        Assert.assertFalse(cond.matches(pktCtx, false));
        cond.tpDstInv = true;
        Assert.assertTrue(cond.matches(pktCtx, false));
        cond.tpDstInv = false;
        cond.tpDst = new Range<>(1233, Transport.MAX_PORT_NO);
        Assert.assertTrue(cond.matches(pktCtx, false));
        cond.tpDstInv = true;
        Assert.assertFalse(cond.matches(pktCtx, false));
        cond.tpDstInv = false;
        cond.tpDst = new Range<>(1233, 1233);
        Assert.assertFalse(cond.matches(pktCtx, false));
        cond.tpDst = new Range<>(1233, 1234);
        Assert.assertTrue(cond.matches(pktCtx, false));
        cond.tpDstInv = true;
        Assert.assertFalse(cond.matches(pktCtx, false));
        cond.tpDstInv = false;
        cond.tpDst = new Range<>(0, 1234);
        Assert.assertTrue(cond.matches(pktCtx, false));
    }

    @Test
    public void testTpDst_upperPorts() {
        pktCtx.wcmatch().setDstPort(50000);

        Condition cond = new Condition();
        pktCtx.inPortId_$eq(UUID.randomUUID());
        Assert.assertTrue(cond.matches(pktCtx, false));
        cond.tpDst = new Range<>(40000, Transport.MAX_PORT_NO);
        Assert.assertTrue(cond.matches(pktCtx, false));
        cond.tpDstInv = true;
        Assert.assertFalse(cond.matches(pktCtx, false));
        cond.tpDstInv = false;
        Assert.assertTrue(cond.matches(pktCtx, false));
        cond.tpDst = new Range<>(55000, Transport.MAX_PORT_NO);
        Assert.assertFalse(cond.matches(pktCtx, false));
        cond.tpDstInv = true;
        Assert.assertTrue(cond.matches(pktCtx, false));
        cond.tpDstInv = false;
        Assert.assertFalse(cond.matches(pktCtx, false));
        cond.tpDst = new Range<>(45000, 55000);
        Assert.assertTrue(cond.matches(pktCtx, false));
        cond.tpDstInv = true;
        Assert.assertFalse(cond.matches(pktCtx, false));
        cond.tpDstInv = false;
        cond.tpDst = new Range<>(0, Transport.MAX_PORT_NO);
        Assert.assertTrue(cond.matches(pktCtx, false));
    }

    /*
    @Test
    public void testFwdFlow() {
        Condition cond = new Condition();
        pktCtx.inPortId = UUID.randomUUID();
        Assert.assertTrue(cond.matches(pktCtx, pktMatch, false));
        Assert.assertFalse(pktCtx.state().isConnectionTracked());
        cond.matchForwardFlow = true;
        Assert.assertTrue(cond.matches(pktCtx, pktMatch, false));
        Assert.assertTrue(pktCtx.state().isConnectionTracked());
        // Still matches forward flow.
        Assert.assertTrue(cond.matches(pktCtx, pktMatch, false));
        cond.matchReturnFlow = true;
        Assert.assertFalse(cond.matches(pktCtx, pktMatch, false));
        cond.matchForwardFlow = false;
        Assert.assertFalse(cond.matches(pktCtx, pktMatch, false));
    }

    @Test
    public void testReturnFlow() {
        Condition cond = new Condition();
        pktCtx.inPortId = UUID.randomUUID();
        connCache.setStoredValue("r");
        Assert.assertTrue(cond.matches(pktCtx, pktMatch, false));
        Assert.assertFalse(pktCtx.state().isConnectionTracked());
        cond.matchForwardFlow = true;
        Assert.assertFalse(cond.matches(pktCtx, pktMatch, false));
        Assert.assertTrue(pktCtx.state().isConnectionTracked());
        cond.matchReturnFlow = true;
        Assert.assertFalse(cond.matches(pktCtx, pktMatch, false));
        cond.matchForwardFlow = false;
        Assert.assertTrue(cond.matches(pktCtx, pktMatch, false));
    }
    */

    @Test
    public void testIpAddrGroup() {
        // Should match with empty condition.
        Condition cond = new Condition();
        Assert.assertTrue(cond.matches(pktCtx, false));

        // Should fail to match with empty IPAddrGroup for source or dest.
        IPAddrGroup ipAddrGroupEmpty = IPAddrGroup.fromAddrs(
                UUID.randomUUID(), new IPAddr[]{});
        cond.ipAddrGroupDst = ipAddrGroupEmpty;
        Assert.assertFalse(cond.matches(pktCtx, false));

        cond = new Condition();
        cond.ipAddrGroupSrc = ipAddrGroupEmpty;
        Assert.assertFalse(cond.matches(pktCtx, false));

        // Should match with inverted empty IPAddrGroup for source or dest.
        cond = new Condition();
        cond.ipAddrGroupDst = ipAddrGroupEmpty;
        cond.invIpAddrGroupIdDst = true;
        Assert.assertTrue(cond.matches(pktCtx, false));

        cond = new Condition();
        cond.ipAddrGroupSrc = ipAddrGroupEmpty;
        cond.invIpAddrGroupIdSrc = true;
        Assert.assertTrue(cond.matches(pktCtx, false));

        // Should match with dest set containing dest address.
        IPAddrGroup ipAddrGroupDst = IPAddrGroup.fromAddrs(UUID.randomUUID(),
                new IPAddr[]{dstIpAddr, new IPv4Addr(0x12345678)});
        cond = new Condition();
        cond.ipAddrGroupDst = ipAddrGroupDst;
        Assert.assertTrue(cond.matches(pktCtx, false));

        // And not when inverted.
        cond.invIpAddrGroupIdDst = true;
        Assert.assertFalse(cond.matches(pktCtx, false));

        // Should not match when source set contains dest, not source, address.
        cond = new Condition();
        cond.ipAddrGroupSrc = ipAddrGroupDst;
        Assert.assertFalse(cond.matches(pktCtx, false));
        cond.invIpAddrGroupIdSrc = true;
        Assert.assertTrue(cond.matches(pktCtx, false));

        // The above with source and dest inverted.
        IPAddrGroup ipAddrGroupSrc = IPAddrGroup.fromAddrs(UUID.randomUUID(),
                new IPAddr[]{srcIpAddr, new IPv4Addr(0x87654321)});
        cond = new Condition();
        cond.ipAddrGroupSrc = ipAddrGroupSrc;
        Assert.assertTrue(cond.matches(pktCtx, false));
        cond.invIpAddrGroupIdSrc = true;
        Assert.assertFalse(cond.matches(pktCtx, false));

        cond = new Condition();
        cond.ipAddrGroupDst = ipAddrGroupSrc;
        Assert.assertFalse(cond.matches(pktCtx, false));
        cond.invIpAddrGroupIdDst = true;
        Assert.assertTrue(cond.matches(pktCtx, false));

        // Should not match when source matches and dest doesn't, or vice-versa.
        cond = new Condition();
        cond.ipAddrGroupDst = ipAddrGroupDst;
        cond.ipAddrGroupSrc = ipAddrGroupDst;
        Assert.assertFalse(cond.matches(pktCtx, false));
        cond.invIpAddrGroupIdSrc = true;
        Assert.assertTrue(cond.matches(pktCtx, false));

        cond = new Condition();
        cond.ipAddrGroupDst = ipAddrGroupSrc;
        cond.ipAddrGroupSrc = ipAddrGroupSrc;
        Assert.assertFalse(cond.matches(pktCtx, false));
        cond.invIpAddrGroupIdDst = true;
        Assert.assertTrue(cond.matches(pktCtx, false));

        // Should match when both match.
        cond = new Condition();
        cond.ipAddrGroupDst = ipAddrGroupDst;
        cond.ipAddrGroupSrc = ipAddrGroupSrc;
    }

    @Test
    public void testIpv6() {
        Condition cond = new Condition();
        cond.etherType = 0x86DD;
        pktCtx.inPortId_$eq(UUID.randomUUID());
        pktCtx.wcmatch().setEtherType((short)0x86DD);
        Assert.assertTrue(cond.matches(pktCtx, false));
    }

    @Test
    public void testSerialization() throws IOException, ClassNotFoundException {
        Condition cond = new Condition();
        Set<UUID> ids = new HashSet<>();
        ids.add(UUID.randomUUID());
        ids.add(UUID.randomUUID());
        cond.inPortIds = ids;
        cond.portGroup = UUID.randomUUID();
        cond.tpSrc = new Range<>(40000, 41000);
        cond.tpSrcInv = false;
        cond.tpDst = new Range<>(42000, 43000);
        cond.tpDstInv = true;
        cond.etherType = 0x86DD;

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        OutputStream out = new BufferedOutputStream(bos);
        JsonGenerator jsonGenerator =
            jsonFactory.createJsonGenerator(new OutputStreamWriter(out));
        jsonGenerator.writeObject(cond);
        out.close();
        byte[] data = bos.toByteArray();
        ByteArrayInputStream bis = new ByteArrayInputStream(data);
        InputStream in = new BufferedInputStream(bis);
        JsonParser jsonParser =
            jsonFactory.createJsonParser(new InputStreamReader(in));
        Condition c = jsonParser.readValueAs(Condition.class);
        in.close();
        Assert.assertTrue(cond.equals(c));
    }
}
