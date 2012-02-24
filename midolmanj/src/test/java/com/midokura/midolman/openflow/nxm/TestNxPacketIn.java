/*
 * Copyright 2012 Midokura Europe SARL
 */

package com.midokura.midolman.openflow.nxm;

import java.nio.ByteBuffer;
import java.util.List;

import org.junit.Test;
import org.openflow.protocol.OFMessage;
import org.openflow.protocol.OFType;
import org.openflow.protocol.OFVendor;
import org.openflow.protocol.factory.BasicFactory;

import com.midokura.midolman.openflow.PacketInReason;


import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.MatcherAssert.assertThat;

public class TestNxPacketIn {

    @Test
    public void test() throws NxmDuplicateEntryException {
        NxMatch nxm = new NxMatch();
        nxm.setInPort((short) 6);
        nxm.setTunnelId(123456);
        nxm.setCookie(987654321);
        byte[] packet = new byte[] {9, 8, 7, 6, 5, 4, 3, 2, 1};
        NxPacketIn pktIn = new NxPacketIn(10, (short) 20,
                PacketInReason.NO_MATCH, (byte) 30, 40, nxm, packet);
        pktIn.prepareSerialize();
        ByteBuffer buff = ByteBuffer.allocate(pktIn.getLength());
        pktIn.writeTo(buff);
        buff.flip();
        NxPacketIn pktIn1 = new NxPacketIn();
        pktIn1.readFrom(buff);
        assertThat("The NxPacketIn read from the ByteBuffer should be " +
                "equal to the one written to the ByteBuffer", pktIn,
                equalTo(pktIn1));
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
        assertThat("The type of the NxMessage should be NxPacketIn",
                nxMsg, instanceOf(NxPacketIn.class));
        pktIn1 = NxPacketIn.class.cast(nxMsg);
        assertThat("The NxPacketIn obtained from NxMessage.fromOFVendor " +
                "should be equal to the original.", pktIn, equalTo(pktIn1));
    }

}
