/*
 * Copyright 2012 Midokura Europe SARL
 */

package com.midokura.midolman.openflow.nxm;

import java.nio.ByteBuffer;

import org.junit.Test;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;

public class TestNxSetFlowFormat {
    @Test
    public void test() {
        NxSetFlowFormat nxm = new NxSetFlowFormat(true);
        ByteBuffer buff = ByteBuffer.allocate(nxm.getLength());
        nxm.writeTo(buff);
        buff.flip();
        NxSetFlowFormat nxm1 = new NxSetFlowFormat();
        nxm1.readFrom(buff);
        assertThat("Serializing then deserializing should give an equivalent " +
                "NxSetFlowFormat", nxm, equalTo(nxm1));

        // Now repeat that for a message with format = false.
        nxm = new NxSetFlowFormat(false);
        buff = ByteBuffer.allocate(nxm.getLength());
        nxm.writeTo(buff);
        buff.flip();
        nxm1 = new NxSetFlowFormat();
        nxm1.readFrom(buff);
        assertThat("Serializing then deserializing should give an equivalent " +
                "NxSetFlowFormat", nxm, equalTo(nxm1));
    }
}
