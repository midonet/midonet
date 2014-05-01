package org.midonet.odp;

import org.junit.Assert;
import org.junit.Test;

import org.midonet.odp.flows.FlowKeyICMPEcho;
import org.midonet.packets.ICMP;

import static org.midonet.odp.flows.FlowKeys.icmpEcho;

public class FlowKeyICMPEchoTest {

    private static final byte ICMP_ECHO_REQ = ICMP.TYPE_ECHO_REQUEST;
    private static final byte ICMP_ECHO_REPLY = ICMP.TYPE_ECHO_REPLY;
    private static final byte CODE_NONE = ICMP.CODE_NONE;

    @Test
    public void testEquals() {
        FlowKeyICMPEcho k1 = icmpEcho(ICMP_ECHO_REQ, CODE_NONE, (short)9507);
        FlowKeyICMPEcho k2 = icmpEcho(ICMP_ECHO_REQ, CODE_NONE, (short)9507);
        FlowKeyICMPEcho k3 = icmpEcho(ICMP_ECHO_REPLY, CODE_NONE, (short)9508);

        Assert.assertEquals(k1, k2);
        Assert.assertEquals(k1.hashCode(), k2.hashCode());
        Assert.assertFalse(k1.equals(k3));
        Assert.assertFalse(k1.hashCode() == k3.hashCode());
        Assert.assertFalse(k2.equals(k3));
        Assert.assertFalse(k2.hashCode() == k3.hashCode());
    }
}
