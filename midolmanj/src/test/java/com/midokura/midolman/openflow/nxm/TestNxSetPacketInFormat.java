/*
 * Copyright 2012 Midokura Europe SARL
 */

package com.midokura.midolman.openflow.nxm;

import java.nio.ByteBuffer;

import org.junit.Test;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;

public class TestNxSetPacketInFormat {

    @Test
    public void test() {
        NxSetPacketInFormat nxm = new NxSetPacketInFormat(true);
        ByteBuffer buff = ByteBuffer.allocate(nxm.getLength());
        nxm.writeTo(buff);
        buff.flip();
        NxSetPacketInFormat nxm1 = new NxSetPacketInFormat();
        nxm1.readFrom(buff);
        assertThat("Serializing then deserializing should give an equivalent " +
                "NxSetFlowFormat", nxm, equalTo(nxm1));

        // Now repeat that for a message with format = false.
        nxm = new NxSetPacketInFormat(false);
        buff = ByteBuffer.allocate(nxm.getLength());
        nxm.writeTo(buff);
        buff.flip();
        nxm1 = new NxSetPacketInFormat();
        nxm1.readFrom(buff);
        assertThat("Serializing then deserializing should give an equivalent " +
                "NxSetFlowFormat", nxm, equalTo(nxm1));
    }
}
