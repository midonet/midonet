package org.midonet.odp;

import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.midonet.odp.flows.FlowKey;
import org.midonet.odp.flows.FlowKeyARP;
import org.midonet.odp.flows.FlowKeyICMP;
import org.midonet.odp.flows.FlowKeyICMPEcho;
import org.midonet.odp.flows.FlowKeyICMPError;
import org.midonet.odp.flows.FlowKeyTCP;
import org.midonet.packets.Ethernet;
import org.midonet.packets.ICMP;
import org.midonet.packets.IPacket;
import org.midonet.packets.IPv4;
import org.midonet.packets.IPv4Addr;
import org.midonet.packets.MAC;
import org.testng.Assert;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

public class FlowMatchTest {

    private List<FlowKey<?>> supported = new ArrayList<FlowKey<?>>();
    private List<FlowKey<?>> unsupported = new ArrayList<FlowKey<?>>();
    private List<FlowKey<?>> tmp = new ArrayList<FlowKey<?>>();

    @SuppressWarnings("unchecked")
    @Before
    public void setUp() {
        supported.add(new FlowKeyICMP());
        supported.add(new FlowKeyARP());
        supported.add(new FlowKeyTCP());
        unsupported.add(new FlowKeyICMPEcho());
        unsupported.add(new FlowKeyICMPError());
    }

    @After
    public void tearDown() {
        supported.clear();
        unsupported.clear();
        tmp = null;
    }

    @Test
    public void testAddKey() {
        FlowMatch m = new FlowMatch();
        assertFalse(m.isUserSpaceOnly());
        for (FlowKey key : supported) {
            m.addKey(key);
        }
        assertFalse(m.isUserSpaceOnly());
        m.addKey(unsupported.get(0));
        assertTrue(m.isUserSpaceOnly());
        assertEquals(supported.size() + 1, m.getKeys().size());
    }

    @Test
    public void testSetKeys() {
        tmp.addAll(supported);
        FlowMatch m = new FlowMatch();
        assertFalse(m.isUserSpaceOnly());
        m.setKeys(tmp);
        assertFalse(m.isUserSpaceOnly());
        m.addKey(unsupported.get(1));
        assertTrue(m.isUserSpaceOnly());
        assertEquals(supported.size() + 1, m.getKeys().size());
    }

    @Test
    public void testConstructWithKeys() {
        tmp.addAll(supported);
        FlowMatch m = new FlowMatch(tmp);
        assertFalse(m.isUserSpaceOnly());
        assertEquals(supported.size(), m.getKeys().size());
        m.addKey(unsupported.get(0));
        assertTrue(m.isUserSpaceOnly());
        assertEquals(supported.size() + 1, m.getKeys().size());

        tmp = new ArrayList<FlowKey<?>>();
        tmp.addAll(supported);
        tmp.addAll(unsupported);
        m = new FlowMatch(tmp);
        assertTrue(m.isUserSpaceOnly());
        assertEquals(supported.size() + unsupported.size(), m.getKeys().size());
    }

    @Test
    public void testConstructFromEthernetWithICMPReplacement() {
        MAC srcMac = MAC.fromString("aa:bb:cc:dd:ee:ff");
        MAC dstMac = MAC.fromString("ff:ee:dd:cc:bb:aa");
        IPv4Addr srcIp = IPv4Addr.fromString("10.0.0.1");
        IPv4Addr dstIp = IPv4Addr.fromString("10.0.0.2");
        ICMP icmp1 = new ICMP();
        ICMP icmp2 = new ICMP();
        ICMP icmp3 = new ICMP();
        ICMP icmp4 = new ICMP();
        icmp1.setEchoRequest((short)9507, (short)10, "hello".getBytes());
        icmp2.setEchoRequest((short)9507, (short)10, "hello".getBytes());
        icmp3.setEchoRequest((short)9508, (short)11, "hello".getBytes());
        icmp4.setEchoRequest((short)9508, (short)12, "hello".getBytes());
        Ethernet eth1 = makeFrame(srcMac, dstMac, srcIp, dstIp, icmp1);
        Ethernet eth2 = makeFrame(srcMac, dstMac, srcIp, dstIp, icmp2);
        Ethernet eth3 = makeFrame(srcMac, dstMac, srcIp, dstIp, icmp3);
        Ethernet eth4 = makeFrame(srcMac, dstMac, srcIp, dstIp, icmp4);
        FlowMatch m1 = new FlowMatch();
        FlowMatch m2 = new FlowMatch();
        FlowMatch m3 = new FlowMatch();
        FlowMatch m4 = new FlowMatch();
        m1.addKey(makeFlowKeyICMP((byte)ICMP.TYPE_ECHO_REQUEST,
                                  (byte)ICMP.CODE_NONE));
        m2.addKey(makeFlowKeyICMP((byte)ICMP.TYPE_ECHO_REQUEST,
                                  (byte)ICMP.CODE_NONE));
        m3.addKey(makeFlowKeyICMP((byte)ICMP.TYPE_ECHO_REQUEST,
                                  (byte)ICMP.CODE_NONE));
        m4.addKey(makeFlowKeyICMP((byte)ICMP.TYPE_ECHO_REQUEST,
                                  (byte)ICMP.CODE_NONE));

        assertEquals(m1, m2);
        assertNotSame(m1, m3);
        assertNotSame(m1, m4);
        assertNotSame(m3, m4);

        FlowMatches.addUserspaceKeys(eth1, m1);
        FlowMatches.addUserspaceKeys(eth2, m2);
        FlowMatches.addUserspaceKeys(eth3, m3);
        FlowMatches.addUserspaceKeys(eth4, m4);

        assertEquals(m1, m2);
        assertNotSame(m1, m3);
        assertNotSame(m1, m4);

    }

    private FlowKeyICMP makeFlowKeyICMP(byte type, byte code) {
        FlowKeyICMP k= new FlowKeyICMP();
        k.setType(type);
        k.setCode(code);
        return k;
    }

    private Ethernet makeFrame(MAC srcMac, MAC dstMac,
                               IPv4Addr srcIp, IPv4Addr dstIp,
                               ICMP payload) {
        IPv4 ipv4 = new IPv4();
        ipv4.setSourceAddress(srcIp);
        ipv4.setDestinationAddress(dstIp);
        ipv4.setProtocol(ICMP.PROTOCOL_NUMBER);
        ipv4.setPayload(payload);
        Ethernet eth = new Ethernet();
        eth.setSourceMACAddress(srcMac);
        eth.setDestinationMACAddress(dstMac);
        eth.setEtherType(IPv4.ETHERTYPE);
        eth.setPayload(ipv4);
        return eth;
    }

}
