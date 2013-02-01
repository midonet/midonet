/*
 * Copyright $year Midokura Pte. Ltd.
 */

package org.midonet.odp.ports;

import org.midonet.netlink.NetlinkMessage;
import org.midonet.netlink.messages.Builder;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class TunnelPortOptionsTest {

    private class TestTunnelPortOptions extends TunnelPortOptions<TestTunnelPortOptions> {

        @Override
        protected TestTunnelPortOptions self() {
            return this;
        }
    }

    private TestTunnelPortOptions testTunnelPortOptions;

    @Before
    public void setUp() throws Exception {
        byte tos = 0b00100101;
        byte ttl = 0b01010010;
        testTunnelPortOptions = new TestTunnelPortOptions();
        testTunnelPortOptions
                .setDestinationIPv4(0x01234567)
                .setSourceIPv4(0x76543210)
                .setFlags(TunnelPortOptions.Flag.TNL_F_CSUM,
                          TunnelPortOptions.Flag.TNL_F_TTL_INHERIT,
                          TunnelPortOptions.Flag.TNL_F_DF_DEFAULT,
                          TunnelPortOptions.Flag.TNL_F_HDR_CACHE)
                .setInKey(0x0123456789ABCDEFL)
                .setOutKey(0x0EDCBA9876543210L)
                .setTos(tos)
                .setTTL(ttl);
    }


    @Test
    public void testLESerialization() throws Exception {
        ByteBuffer byteBuffer = ByteBuffer.allocate(1024);
        byteBuffer.order(ByteOrder.LITTLE_ENDIAN);
        Builder builder = new Builder(byteBuffer);
        testTunnelPortOptions.serialize(builder);
        byteBuffer.rewind();

        NetlinkMessage netlinkMessage = new NetlinkMessage(byteBuffer);
        TestTunnelPortOptions copyTunnelPortOptions = new TestTunnelPortOptions();
        copyTunnelPortOptions.deserialize(netlinkMessage);

        assert(testTunnelPortOptions.equals(copyTunnelPortOptions));
    }

    @Test
    public void testBESerialization() throws Exception {
        ByteBuffer byteBuffer = ByteBuffer.allocate(1024);
        byteBuffer.order(ByteOrder.BIG_ENDIAN);
        Builder builder = new Builder(byteBuffer);
        testTunnelPortOptions.serialize(builder);
        byteBuffer.rewind();

        NetlinkMessage netlinkMessage = new NetlinkMessage(byteBuffer);
        TestTunnelPortOptions copyTunnelPortOptions = new TestTunnelPortOptions();
        copyTunnelPortOptions.deserialize(netlinkMessage);

        assert(testTunnelPortOptions.equals(copyTunnelPortOptions));
    }

}
