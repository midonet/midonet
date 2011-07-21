/* Copyright 2011 Midokura Inc. */

package com.midokura.midolman.openflow;

import com.midokura.midolman.openflow.MidoMatch;

import junit.framework.TestCase;
import org.junit.Assert;
import org.junit.Test;
import org.openflow.protocol.OFMatch;


public class TestMidoMatch extends TestCase {

    @Test
    public void testDefaultCtor() {
        OFMatch mmatch = new MidoMatch();
        Assert.assertEquals(OFMatch.OFPFW_ALL, mmatch.getWildcards());
    }

    @Test
    public void testSetDlDest() {
        OFMatch mmatch = new MidoMatch();
        byte[] dlDest = { 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f };
        String dlDestStr = "0a:0b:0c:0d:0e:0f";
        mmatch.setDataLayerDestination(dlDest);
        Assert.assertArrayEquals(dlDest, mmatch.getDataLayerDestination());
        Assert.assertEquals(OFMatch.OFPFW_ALL & ~OFMatch.OFPFW_DL_DST,
                            mmatch.getWildcards());
        mmatch = new MidoMatch();
        mmatch.setDataLayerDestination(dlDestStr);
        Assert.assertArrayEquals(dlDest, mmatch.getDataLayerDestination());
        Assert.assertEquals(OFMatch.OFPFW_ALL & ~OFMatch.OFPFW_DL_DST,
                            mmatch.getWildcards());
    }

    @Test
    public void testSetDlSource() {
        OFMatch mmatch = new MidoMatch();
        byte[] dlSource = { 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f };
        String dlSourceStr = "0a:0b:0c:0d:0e:0f";
        mmatch.setDataLayerSource(dlSource);
        Assert.assertArrayEquals(dlSource, mmatch.getDataLayerSource());
        Assert.assertEquals(OFMatch.OFPFW_ALL & ~OFMatch.OFPFW_DL_SRC,
                            mmatch.getWildcards());
        mmatch = new MidoMatch();
        mmatch.setDataLayerSource(dlSourceStr);
        Assert.assertArrayEquals(dlSource, mmatch.getDataLayerSource());
        Assert.assertEquals(OFMatch.OFPFW_ALL & ~OFMatch.OFPFW_DL_SRC,
                            mmatch.getWildcards());
    }
}
