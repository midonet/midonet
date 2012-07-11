/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.util.netlink.protos;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;

import org.mockito.Matchers;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.powermock.api.mockito.PowerMockito;

import com.midokura.util.netlink.AbstractNetlinkConnection;
import com.midokura.util.netlink.Netlink;
import com.midokura.util.netlink.NetlinkChannel;
import com.midokura.util.reactor.Reactor;

/**
 * // TODO: mtoader ! Please explain yourself.
 */
public abstract class AbstractNetlinkProtocolTest<NetlinkConnection extends AbstractNetlinkConnection> {

    NetlinkChannel channel = PowerMockito.mock(NetlinkChannel.class);
    Reactor reactor = PowerMockito.mock(Reactor.class);

    NetlinkConnection connection;

    public void setUp(final byte[][] responses) throws Exception {
        Netlink.Address remote = new Netlink.Address(0);
        Netlink.Address local = new Netlink.Address(294);

        PowerMockito.when(channel.getRemoteAddress())
                    .thenReturn(remote);

        PowerMockito.when(channel.getLocalAddress())
                    .thenReturn(local);

        Answer<Object> playbackResponseAnswer = new Answer<Object>() {
            int position = 0;

            @Override
            public Object answer(InvocationOnMock invocation)
                throws Throwable {
                ByteBuffer result = ((ByteBuffer) invocation.getArguments()[0]);
                result.put(responses[position]);
                position++;
                return result.position();
            }
        };

        PowerMockito.when(channel.read(Matchers.<ByteBuffer>any()))
                    .then(playbackResponseAnswer);
    }

    protected void fireNewReply() throws IOException {
        connection.handleEvent(null);
    }

    private static String HEXES = "0123456789ABCDEF";

    protected byte[] macFromString(String macAddress) {
        byte[] address = new byte[6];
        String[] macBytes = macAddress.split(":");
        if (macBytes.length != 6)
            throw new IllegalArgumentException(
                "Specified MAC Address must contain 12 hex digits" +
                    " separated pairwise by :'s.");

        for (int i = 0; i < 6; ++i) {
            address[i] = (byte) (
                (HEXES.indexOf(macBytes[i].toUpperCase().charAt(0)) << 4) |
                    HEXES.indexOf(macBytes[i].toUpperCase().charAt(1))
            );
        }

        return address;

    }

    protected byte[] ipFromString(String ip) {
        try {
            return Inet4Address.getByName(ip).getAddress();
        } catch (UnknownHostException e) {
            return new byte[4];
        }
    }
}
