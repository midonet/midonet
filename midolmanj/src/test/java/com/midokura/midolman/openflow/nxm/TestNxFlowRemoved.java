/*
 * Copyright 2012 Midokura Europe SARL
 */

package com.midokura.midolman.openflow.nxm;

import java.nio.ByteBuffer;
import java.util.List;

import org.junit.Test;
import org.openflow.protocol.OFFlowRemoved;
import org.openflow.protocol.OFMessage;
import org.openflow.protocol.OFType;
import org.openflow.protocol.OFVendor;
import org.openflow.protocol.factory.BasicFactory;


import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.MatcherAssert.assertThat;

public class TestNxFlowRemoved {

    // This is a helper method, not a test.
    private void testFlowRemoved(NxMatch nxm) {
        // Now add the match to an NxFlowRemoved.
        NxFlowRemoved flowRem = new NxFlowRemoved(10, (short) 20,
                OFFlowRemoved.OFFlowRemovedReason.OFPRR_IDLE_TIMEOUT,
                30, 40, (short) 50, 60, 70, nxm);
        flowRem.prepareSerialize();
        ByteBuffer buff = ByteBuffer.allocate(flowRem.getLength());
        flowRem.writeTo(buff);
        buff.flip();
        NxFlowRemoved flowRem1 = new NxFlowRemoved();
        flowRem1.readFrom(buff);
        assertThat("The NxFlowRemoved read from the ByteBuffer should be " +
                "equal to the one written to the ByteBuffer", flowRem,
                equalTo(flowRem1));
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
        assertThat("The type of the NxMessage should be NxFlowRemoved",
                nxMsg, instanceOf(NxFlowRemoved.class));
        flowRem1 = NxFlowRemoved.class.cast(nxMsg);
        assertThat("The NxFlowRemoved obtained from NxMessage.fromOFVendor " +
                "should be equal to the original.", flowRem, equalTo(flowRem1));
    }

    @Test
    public void test() throws NxmIOException {
        testFlowRemoved(MatchTranslation.toNxMatch(Match.arp(), 0));
        testFlowRemoved(MatchTranslation.toNxMatch(Match.tcp(), 0));
        testFlowRemoved(MatchTranslation.toNxMatch(Match.udp(), 0));
        testFlowRemoved(MatchTranslation.toNxMatch(Match.icmp(), 0));
        testFlowRemoved(MatchTranslation.toNxMatch(Match.ipv6(), 0));
    }

}
