package org.midonet.odp;

import org.junit.Test;
import org.midonet.odp.flows.FlowKeyICMPEcho;
import org.midonet.packets.ICMP;
import org.testng.Assert;

public class FlowKeyICMPEchoTest {

    private static final byte ICMP_ECHO_REQ = (byte)ICMP.TYPE_ECHO_REQUEST;
    private static final byte ICMP_ECHO_REPLY = (byte)ICMP.TYPE_ECHO_REPLY;
    private static final byte CODE_NONE = (byte)ICMP.CODE_NONE;

    @Test
    public void testEquals() {
        FlowKeyICMPEcho k1 = new FlowKeyICMPEcho();
        FlowKeyICMPEcho k2 = new FlowKeyICMPEcho();
        FlowKeyICMPEcho k3 = new FlowKeyICMPEcho();
        FlowKeyICMPEcho k4 = new FlowKeyICMPEcho();

        k1.setType(ICMP_ECHO_REQ);
        k2.setType(ICMP_ECHO_REQ);
        k3.setType(ICMP_ECHO_REPLY);
        k4.setType(ICMP_ECHO_REQ);

        k1.setCode(CODE_NONE);
        k2.setCode(CODE_NONE);
        k3.setCode(CODE_NONE);
        k3.setCode(CODE_NONE);

        k1.setIdentifier((short)9507);
        k1.setSeq((short)7);
        k2.setIdentifier((short)9507);
        k2.setSeq((short)7);
        k3.setIdentifier((short)9508);
        k3.setSeq((short)7);
        k4.setIdentifier((short)9507);
        k4.setSeq((short)234);

        Assert.assertEquals(k1, k2);
        Assert.assertEquals(k1.hashCode(), k2.hashCode());
        Assert.assertNotEquals(k1, k3);
        Assert.assertNotEquals(k1, k4);
        Assert.assertNotEquals(k1.hashCode(), k3.hashCode());
        Assert.assertNotEquals(k1.hashCode(), k4.hashCode());
        Assert.assertNotEquals(k2, k3);
        Assert.assertNotEquals(k2, k4);
        Assert.assertNotEquals(k2.hashCode(), k3.hashCode());
        Assert.assertNotEquals(k2.hashCode(), k4.hashCode());
    }

}
