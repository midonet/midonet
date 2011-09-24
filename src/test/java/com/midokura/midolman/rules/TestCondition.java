package com.midokura.midolman.rules;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.util.HashSet;
import java.util.Random;
import java.util.UUID;

import org.codehaus.jackson.JsonFactory;
import org.codehaus.jackson.JsonGenerator;
import org.codehaus.jackson.JsonParser;
import org.codehaus.jackson.JsonProcessingException;
import org.codehaus.jackson.annotate.JsonAutoDetect.Visibility;
import org.codehaus.jackson.map.ObjectMapper;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import com.midokura.midolman.openflow.MidoMatch;

public class TestCondition {

    static MidoMatch pktMatch;
    static Random rand;
    private static ObjectMapper objectMapper = new ObjectMapper();
    private static JsonFactory jsonFactory = new JsonFactory(objectMapper);

    static {
        objectMapper.setVisibilityChecker(
            objectMapper.getVisibilityChecker().withFieldVisibility(Visibility.ANY));
        objectMapper.enableDefaultTyping(ObjectMapper.DefaultTyping.NON_FINAL);
    }

    @BeforeClass
    public static void setup() {
        pktMatch = new MidoMatch();
        pktMatch.setInputPort((short) 5);
        pktMatch.setDataLayerSource("02:11:33:00:11:01");
        pktMatch.setDataLayerDestination("02:11:aa:ee:22:05");
        pktMatch.setNetworkSource(0x0a001406, 32);
        pktMatch.setNetworkDestination(0x0a000b22, 32);
        pktMatch.setNetworkProtocol((byte) 8);
        pktMatch.setNetworkTypeOfService((byte) 34);
        pktMatch.setTransportSource((short) 4321);
        pktMatch.setTransportDestination((short) 1234);
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

    @Test
    public void testInPortIds() {
        Condition cond = new Condition();
        UUID inPort = new UUID(rand.nextLong(), rand.nextLong());
        cond.inPortIds = new HashSet<UUID>();
        cond.inPortIds.add(new UUID(rand.nextLong(), rand.nextLong()));
        cond.inPortIds.add(new UUID(rand.nextLong(), rand.nextLong()));
        // The condition should not match the packet.
        Assert.assertFalse(cond.matches(inPort, null, pktMatch));
        Assert.assertFalse(cond.matches(null, null, pktMatch));
        // Verify that inPortInv causes a match.
        cond.inPortInv = true;
        Assert.assertTrue(cond.matches(inPort, null, pktMatch));
        Assert.assertTrue(cond.matches(null, null, pktMatch));
        // Now add inPort to the condition - it stops matching due to invert.
        cond.inPortIds.add(inPort);
        Assert.assertFalse(cond.matches(inPort, null, pktMatch));
        cond.inPortInv = false;
        Assert.assertTrue(cond.matches(inPort, null, pktMatch));
    }

    @Test
    public void testOutPortIds() {
        Condition cond = new Condition();
        UUID inPort = new UUID(rand.nextLong(), rand.nextLong());
        UUID outPort = new UUID(rand.nextLong(), rand.nextLong());
        cond.outPortIds = new HashSet<UUID>();
        cond.outPortIds.add(new UUID(rand.nextLong(), rand.nextLong()));
        cond.outPortIds.add(new UUID(rand.nextLong(), rand.nextLong()));
        // The condition should not match the packet.
        Assert.assertFalse(cond.matches(inPort, outPort, pktMatch));
        Assert.assertFalse(cond.matches(inPort, null, pktMatch));
        // Verify that inPortInv causes a match.
        cond.outPortInv = true;
        Assert.assertTrue(cond.matches(inPort, outPort, pktMatch));
        Assert.assertTrue(cond.matches(inPort, null, pktMatch));
        // Now add inPort to the condition - it stops matching due to invert.
        cond.outPortIds.add(outPort);
        Assert.assertFalse(cond.matches(inPort, outPort, pktMatch));
        cond.outPortInv = false;
        Assert.assertTrue(cond.matches(inPort, outPort, pktMatch));
    }

    @Test
    public void testNwTos() {
        Condition cond = new Condition();
        UUID inPort = new UUID(rand.nextLong(), rand.nextLong());
        Assert.assertTrue(cond.matches(inPort, null, pktMatch));
        cond.nwTos = 5;
        Assert.assertFalse(cond.matches(inPort, null, pktMatch));
        cond.nwTosInv = true;
        Assert.assertTrue(cond.matches(inPort, null, pktMatch));
        cond.nwTosInv = false;
        cond.nwTos = 34;
        Assert.assertTrue(cond.matches(inPort, null, pktMatch));
        cond.nwTosInv = true;
        Assert.assertFalse(cond.matches(inPort, null, pktMatch));
    }

    @Test
    public void testNwProto() {
        Condition cond = new Condition();
        UUID inPort = new UUID(rand.nextLong(), rand.nextLong());
        Assert.assertTrue(cond.matches(inPort, null, pktMatch));
        cond.nwProto = 5;
        Assert.assertFalse(cond.matches(inPort, null, pktMatch));
        cond.nwProtoInv = true;
        Assert.assertTrue(cond.matches(inPort, null, pktMatch));
        cond.nwProtoInv = false;
        cond.nwProto = 8;
        Assert.assertTrue(cond.matches(inPort, null, pktMatch));
        cond.nwProtoInv = true;
        Assert.assertFalse(cond.matches(inPort, null, pktMatch));
    }

    @Test
    public void testNwSrc() {
        Condition cond = new Condition();
        UUID inPort = new UUID(rand.nextLong(), rand.nextLong());
        Assert.assertTrue(cond.matches(inPort, null, pktMatch));
        // Inverting nwSrc has no effect when it's wild-carded.
        cond.nwSrcInv = true;
        Assert.assertTrue(cond.matches(inPort, null, pktMatch));
        cond.nwSrcInv = false;
        // Set the nwSrcIp to something different than the packet's 0x0a001406.
        cond.nwSrcIp = 0x0a001403;
        // Since nwSrcLength is still 0, the condition still matches the packet.
        Assert.assertTrue(cond.matches(inPort, null, pktMatch));
        cond.nwSrcLength = 32;
        Assert.assertFalse(cond.matches(inPort, null, pktMatch));
        // Now try shorter prefixes:
        cond.nwSrcLength = 24;
        Assert.assertTrue(cond.matches(inPort, null, pktMatch));
        cond.nwSrcLength = 16;
        Assert.assertTrue(cond.matches(inPort, null, pktMatch));
        // Now try length 0 with an ip that differs in the left-most bit.
        cond.nwSrcIp = 0xfa010104;
        cond.nwSrcLength = 1;
        Assert.assertFalse(cond.matches(inPort, null, pktMatch));
        cond.nwSrcLength = 0;
        Assert.assertTrue(cond.matches(inPort, null, pktMatch));
        // nwSrcLength = 0 is like wild-carding. So inverting is ignored.
        cond.nwSrcInv = true;
        Assert.assertTrue(cond.matches(inPort, null, pktMatch));
        cond.nwSrcLength = 32;
        Assert.assertTrue(cond.matches(inPort, null, pktMatch));
        // Remove the invert, set the nwSrcIp to the packet's
        cond.nwSrcInv = false;
        Assert.assertFalse(cond.matches(inPort, null, pktMatch));
        cond.nwSrcIp = 0x0a001406;
        Assert.assertTrue(cond.matches(inPort, null, pktMatch));
        cond.nwSrcInv = true;
        Assert.assertFalse(cond.matches(inPort, null, pktMatch));
    }

    @Test
    public void testNwDst() {
        Condition cond = new Condition();
        UUID inPort = new UUID(rand.nextLong(), rand.nextLong());
        Assert.assertTrue(cond.matches(inPort, null, pktMatch));
        // Set the nwDstIp to something different than the packet's 0x0a000b22.
        cond.nwDstIp = 0x0a000b23;
        // Since nwDstLength is still 0, the condition still matches the packet.
        Assert.assertTrue(cond.matches(inPort, null, pktMatch));
        // Similarly, invert is ignored while nwDstLength is zero.
        cond.nwDstInv = true;
        Assert.assertTrue(cond.matches(inPort, null, pktMatch));
        cond.nwDstInv = false;
        cond.nwDstLength = 32;
        Assert.assertFalse(cond.matches(inPort, null, pktMatch));
        // Now try shorter prefixes:
        cond.nwDstLength = 31;
        Assert.assertTrue(cond.matches(inPort, null, pktMatch));
        cond.nwDstLength = 24;
        Assert.assertTrue(cond.matches(inPort, null, pktMatch));
        cond.nwDstLength = 16;
        Assert.assertTrue(cond.matches(inPort, null, pktMatch));
        // Now try inverting
        cond.nwDstInv = true;
        Assert.assertFalse(cond.matches(inPort, null, pktMatch));
        cond.nwDstLength = 32;
        Assert.assertTrue(cond.matches(inPort, null, pktMatch));
        // Remove the invert, set the nwDstIp to the packet's
        cond.nwDstInv = false;
        cond.nwDstIp = 0x0a000b22;
        Assert.assertTrue(cond.matches(inPort, null, pktMatch));
        cond.nwDstInv = true;
        Assert.assertFalse(cond.matches(inPort, null, pktMatch));
    }

    @Test
    public void testTpSrc() {
        Condition cond = new Condition();
        UUID inPort = new UUID(rand.nextLong(), rand.nextLong());
        Assert.assertTrue(cond.matches(inPort, null, pktMatch));
        cond.tpSrcStart = 30;
        // While tpSrcEnd is 0, this is still considered wild-carded
        Assert.assertTrue(cond.matches(inPort, null, pktMatch));
        cond.tpSrcInv = true;
        Assert.assertTrue(cond.matches(inPort, null, pktMatch));
        cond.tpSrcInv = false;
        cond.tpSrcEnd = 4320;
        Assert.assertFalse(cond.matches(inPort, null, pktMatch));
        cond.tpSrcInv = true;
        Assert.assertTrue(cond.matches(inPort, null, pktMatch));
        cond.tpSrcInv = false;
        cond.tpSrcEnd = 4321;
        Assert.assertTrue(cond.matches(inPort, null, pktMatch));
        cond.tpSrcStart = 4321;
        Assert.assertTrue(cond.matches(inPort, null, pktMatch));
        cond.tpSrcInv = true;
        Assert.assertFalse(cond.matches(inPort, null, pktMatch));
        cond.tpSrcInv = false;
        cond.tpSrcStart = 4322;
        Assert.assertFalse(cond.matches(inPort, null, pktMatch));
    }

    @Test
    public void testTpDst() {
        Condition cond = new Condition();
        UUID inPort = new UUID(rand.nextLong(), rand.nextLong());
        Assert.assertTrue(cond.matches(inPort, null, pktMatch));
        cond.tpDstStart = 30;
        // While tpDstEnd is 0, this is still considered wild-carded
        Assert.assertTrue(cond.matches(inPort, null, pktMatch));
        cond.tpDstInv = true;
        Assert.assertTrue(cond.matches(inPort, null, pktMatch));
        cond.tpDstInv = false;
        cond.tpDstEnd = 1233;
        Assert.assertFalse(cond.matches(inPort, null, pktMatch));
        cond.tpDstInv = true;
        Assert.assertTrue(cond.matches(inPort, null, pktMatch));
        cond.tpDstInv = false;
        cond.tpDstEnd = 1234;
        Assert.assertTrue(cond.matches(inPort, null, pktMatch));
        cond.tpDstStart = 1234;
        Assert.assertTrue(cond.matches(inPort, null, pktMatch));
        cond.tpDstInv = true;
        Assert.assertFalse(cond.matches(inPort, null, pktMatch));
        cond.tpDstInv = false;
        cond.tpDstStart = 1235;
        Assert.assertFalse(cond.matches(inPort, null, pktMatch));
    }

    @Test
    public void testSerialization() throws IOException, ClassNotFoundException {
        Condition cond = new Condition();
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
