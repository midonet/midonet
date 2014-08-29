package org.midonet.odp;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import org.junit.Assert;
import org.junit.Test;

import org.midonet.netlink.BytesUtil;
import org.midonet.odp.flows.FlowKeyTCPFlags;
import org.midonet.packets.TCP;

import static org.midonet.odp.flows.FlowKeys.tcpFlags;

public class FlowKeyTCPFlagsTest {

    @Test
    public void testGetSetFlag() {
        List<TCP.Flag> flagsList = new ArrayList<TCP.Flag>();
        flagsList.add(TCP.Flag.Ack);

        FlowKeyTCPFlags flags1 = tcpFlags(flagsList);

        Assert.assertTrue(flags1.getFlag(TCP.Flag.Ack));
        Assert.assertFalse(flags1.getFlag(TCP.Flag.Syn));
        Assert.assertFalse(flags1.getFlag(TCP.Flag.Fin));

        flags1.setFlag(TCP.Flag.Ack, false);
        flags1.setFlag(TCP.Flag.Syn, true);
        flags1.setFlag(TCP.Flag.Fin, true);

        Assert.assertFalse(flags1.getFlag(TCP.Flag.Ack));
        Assert.assertTrue(flags1.getFlag(TCP.Flag.Syn));
        Assert.assertTrue(flags1.getFlag(TCP.Flag.Fin));
    }

    @Test
    public void testSerialization() {
        List<TCP.Flag> flagsList = new ArrayList<TCP.Flag>();
        flagsList.add(TCP.Flag.Ack);
        flagsList.add(TCP.Flag.Rst);

        FlowKeyTCPFlags flags1 = tcpFlags(flagsList);
        FlowKeyTCPFlags flags2 = tcpFlags((short) 0);

        ByteBuffer buf = BytesUtil.instance.allocate(256);
        flags1.serializeInto(buf);
        buf.flip();
        flags2.deserializeFrom(buf);

        Assert.assertEquals(flags1, flags2);
        Assert.assertTrue(flags2.getFlag(TCP.Flag.Ack));
        Assert.assertTrue(flags2.getFlag(TCP.Flag.Rst));
    }
}

