/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.odp.protos;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import org.midonet.netlink.BufferPool;
import org.midonet.netlink.Callback;
import org.midonet.netlink.exceptions.NetlinkException;
import org.midonet.netlink.protos.NetlinkConnection;


//@RunWith(PowerMockRunner.class)
public class NetlinkConnectionTest extends AbstractNetlinkProtocolTest {

    byte[][] responses = new byte[][]{
        {
            (byte) 0xC0, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x10, (byte) 0x00,
            (byte) 0x00, (byte) 0x00, (byte) 0x01, (byte) 0x00, (byte) 0x00, (byte) 0x00,
            (byte) 0x89, (byte) 0x27, (byte) 0x00, (byte) 0x00, (byte) 0x01, (byte) 0x02,
            (byte) 0x00, (byte) 0x00, (byte) 0x11, (byte) 0x00, (byte) 0x02, (byte) 0x00,
            (byte) 0x6F, (byte) 0x76, (byte) 0x73, (byte) 0x5F, (byte) 0x64, (byte) 0x61,
            (byte) 0x74, (byte) 0x61, (byte) 0x70, (byte) 0x61, (byte) 0x74, (byte) 0x68,
            (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x06, (byte) 0x00,
            (byte) 0x01, (byte) 0x00, (byte) 0x18, (byte) 0x00, (byte) 0x00, (byte) 0x00,
            (byte) 0x08, (byte) 0x00, (byte) 0x03, (byte) 0x00, (byte) 0x01, (byte) 0x00,
            (byte) 0x00, (byte) 0x00, (byte) 0x08, (byte) 0x00, (byte) 0x04, (byte) 0x00,
            (byte) 0x04, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x08, (byte) 0x00,
            (byte) 0x05, (byte) 0x00, (byte) 0x03, (byte) 0x00, (byte) 0x00, (byte) 0x00,
            (byte) 0x54, (byte) 0x00, (byte) 0x06, (byte) 0x00, (byte) 0x14, (byte) 0x00,
            (byte) 0x01, (byte) 0x00, (byte) 0x08, (byte) 0x00, (byte) 0x01, (byte) 0x00,
            (byte) 0x01, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x08, (byte) 0x00,
            (byte) 0x02, (byte) 0x00, (byte) 0x0B, (byte) 0x00, (byte) 0x00, (byte) 0x00,
            (byte) 0x14, (byte) 0x00, (byte) 0x02, (byte) 0x00, (byte) 0x08, (byte) 0x00,
            (byte) 0x01, (byte) 0x00, (byte) 0x02, (byte) 0x00, (byte) 0x00, (byte) 0x00,
            (byte) 0x08, (byte) 0x00, (byte) 0x02, (byte) 0x00, (byte) 0x0B, (byte) 0x00,
            (byte) 0x00, (byte) 0x00, (byte) 0x14, (byte) 0x00, (byte) 0x03, (byte) 0x00,
            (byte) 0x08, (byte) 0x00, (byte) 0x01, (byte) 0x00, (byte) 0x03, (byte) 0x00,
            (byte) 0x00, (byte) 0x00, (byte) 0x08, (byte) 0x00, (byte) 0x02, (byte) 0x00,
            (byte) 0x0E, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x14, (byte) 0x00,
            (byte) 0x04, (byte) 0x00, (byte) 0x08, (byte) 0x00, (byte) 0x01, (byte) 0x00,
            (byte) 0x04, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x08, (byte) 0x00,
            (byte) 0x02, (byte) 0x00, (byte) 0x0B, (byte) 0x00, (byte) 0x00, (byte) 0x00,
            (byte) 0x24, (byte) 0x00, (byte) 0x07, (byte) 0x00, (byte) 0x20, (byte) 0x00,
            (byte) 0x01, (byte) 0x00, (byte) 0x08, (byte) 0x00, (byte) 0x02, (byte) 0x00,
            (byte) 0x03, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x11, (byte) 0x00,
            (byte) 0x01, (byte) 0x00, (byte) 0x6F, (byte) 0x76, (byte) 0x73, (byte) 0x5F,
            (byte) 0x64, (byte) 0x61, (byte) 0x74, (byte) 0x61, (byte) 0x70, (byte) 0x61,
            (byte) 0x74, (byte) 0x68, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00
        }
    };


    @Before
    public void setUp() throws Exception {
        super.setUp(responses);
        setConnection();
        connection.bypassSendQueue(true);
        connection.setMaxBatchIoOps(1);
    }

    @After
    public void tearDown() throws Exception {
        super.tearDown();
    }

    @Test
    public void testGetFamilyId() throws Exception {

        final AtomicInteger dpId = new AtomicInteger(0);

        Callback<Short> cb = new Callback<Short>() {
            public void onSuccess(Short s) { dpId.set(s); }
            public void onError(NetlinkException e) { }
            public void onTimeout() {}
        };

        connection.getFamilyId("ovs_datapath", cb);

        exchangeMessage();

        Assert.assertEquals("The datapath id was properly parsed from packet data",
                            dpId.get(), 24);
    }
}
