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
        mmatch.setDataLayerDestination(dlDest);
        Assert.assertEquals(dlDest, mmatch.getDataLayerDestination());
    }
}
        
