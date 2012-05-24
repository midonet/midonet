/*
 * Copyright 2012 Midokura Europe SARL
 */

package com.midokura.midolman.openflow.nxm;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;
import org.openflow.protocol.OFMessage;
import org.openflow.protocol.OFType;
import org.openflow.protocol.OFVendor;
import org.openflow.protocol.action.OFAction;
import org.openflow.protocol.action.OFActionOutput;
import org.openflow.protocol.factory.BasicFactory;


import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.MatcherAssert.assertThat;

public class TestNxFlowMod {

    // This is a helper method, not a test.
    private void testFlowMod(NxMatch nxm) {
        // Now add the match to an NxFlowMod.
        ArrayList<OFAction> actions = new ArrayList<OFAction>();
        actions.add(new NxActionSetTunnelKey32(123456));
        actions.add(new NxActionSetTunnelKey64(123456789));
        actions.add(new OFActionOutput((short)4, (short)0));
        NxFlowMod flowMod = NxFlowMod.flowModAdd(nxm, actions, 10, 20,
                (short)30, (short)40 , (short)50, (short)60);
        flowMod.prepareSerialize();
        ByteBuffer buff = ByteBuffer.allocate(flowMod.getLength());
        flowMod.writeTo(buff);
        buff.flip();
        NxFlowMod flowMod1 = new NxFlowMod();
        flowMod1.readFrom(buff);
        assertThat("The NxFlowMod read from the ByteBuffer should be equal to" +
                " the NxFlowMod written to the ByteBuffer", flowMod,
                equalTo(flowMod1));
        // Also try deserializing into an OFVendor instance and then converting
        // to NxMessage.
        BasicFactory factory = new BasicFactory();
        buff.flip();
        List<OFMessage> messages = factory.parseMessages(buff);
        assertThat("There should be only one parsed message.", 1,
                equalTo(messages.size()));
        OFMessage msg = messages.get(0);
        assertThat("The type of the parsed message should be VENDOR",
                OFType.VENDOR, equalTo(msg.getType()));
        OFVendor vm = OFVendor.class.cast(msg);
        NxMessage nxMsg = NxMessage.fromOFVendor(vm);
        assertThat("The type of the NxMessage should be NxFlowMod",
                nxMsg, instanceOf(NxFlowMod.class));
        flowMod1 = NxFlowMod.class.cast(nxMsg);
        assertThat("The NxFlowMod obtained from NxMessage.fromOFVendor " +
                "should be equal to the original.", flowMod, equalTo(flowMod1));
    }

    @Test
    public void test() throws NxmIOException {
        testFlowMod(MatchTranslation.toNxMatch(Match.arp(), 0, 0));
        testFlowMod(MatchTranslation.toNxMatch(Match.arpInVlan(), 0, 0));
        testFlowMod(MatchTranslation.toNxMatch(Match.tcp(), 0, 0));
        testFlowMod(MatchTranslation.toNxMatch(Match.udp(), 0, 0));
        testFlowMod(MatchTranslation.toNxMatch(Match.icmp(), 0, 0));
        testFlowMod(MatchTranslation.toNxMatch(Match.ipv6(), 0, 0));
    }
}
