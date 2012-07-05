/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.util.netlink.protos;

import java.nio.ByteBuffer;
import java.util.Set;
import java.util.concurrent.Future;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Matchers;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.powermock.api.mockito.PowerMockito;
import static org.hamcrest.CoreMatchers.hasItems;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import com.midokura.util.netlink.Netlink;
import com.midokura.util.netlink.NetlinkChannel;
import com.midokura.util.netlink.dp.Datapath;
import com.midokura.util.netlink.dp.Port;
import com.midokura.util.netlink.dp.Ports;
import com.midokura.util.reactor.Reactor;

/**
 * // TODO: Explain yourself.
 */
public class OvsDatapathConnectionTest {

    OvsDatapathConnection connection;

    NetlinkChannel channel = PowerMockito.mock(NetlinkChannel.class);
    Reactor reactor = PowerMockito.mock(Reactor.class);

    @Before
    public void setUp() throws Exception {
        Netlink.Address remote = new Netlink.Address(0);
        Netlink.Address local = new Netlink.Address(294);

        PowerMockito.when(channel.getRemoteAddress())
                    .thenReturn(remote);

        PowerMockito.when(channel.getLocalAddress())
                    .thenReturn(local);

        connection = new OvsDatapathConnection(channel, reactor);
    }

    @Test
    public void testEnumerateDatapaths() throws Exception {
        final byte[][] responses = {
            {
                (byte) 0xC0, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x10,
                (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x01, (byte) 0x00,
                (byte) 0x00, (byte) 0x00, (byte) 0xBA, (byte) 0x03, (byte) 0x00,
                (byte) 0x00, (byte) 0x01, (byte) 0x02, (byte) 0x00, (byte) 0x00,
                (byte) 0x11, (byte) 0x00, (byte) 0x02, (byte) 0x00, (byte) 0x6F,
                (byte) 0x76, (byte) 0x73, (byte) 0x5F, (byte) 0x64, (byte) 0x61,
                (byte) 0x74, (byte) 0x61, (byte) 0x70, (byte) 0x61, (byte) 0x74,
                (byte) 0x68, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                (byte) 0x06, (byte) 0x00, (byte) 0x01, (byte) 0x00, (byte) 0x18,
                (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x08, (byte) 0x00,
                (byte) 0x03, (byte) 0x00, (byte) 0x01, (byte) 0x00, (byte) 0x00,
                (byte) 0x00, (byte) 0x08, (byte) 0x00, (byte) 0x04, (byte) 0x00,
                (byte) 0x04, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x08,
                (byte) 0x00, (byte) 0x05, (byte) 0x00, (byte) 0x03, (byte) 0x00,
                (byte) 0x00, (byte) 0x00, (byte) 0x54, (byte) 0x00, (byte) 0x06,
                (byte) 0x00, (byte) 0x14, (byte) 0x00, (byte) 0x01, (byte) 0x00,
                (byte) 0x08, (byte) 0x00, (byte) 0x01, (byte) 0x00, (byte) 0x01,
                (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x08, (byte) 0x00,
                (byte) 0x02, (byte) 0x00, (byte) 0x0B, (byte) 0x00, (byte) 0x00,
                (byte) 0x00, (byte) 0x14, (byte) 0x00, (byte) 0x02, (byte) 0x00,
                (byte) 0x08, (byte) 0x00, (byte) 0x01, (byte) 0x00, (byte) 0x02,
                (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x08, (byte) 0x00,
                (byte) 0x02, (byte) 0x00, (byte) 0x0B, (byte) 0x00, (byte) 0x00,
                (byte) 0x00, (byte) 0x14, (byte) 0x00, (byte) 0x03, (byte) 0x00,
                (byte) 0x08, (byte) 0x00, (byte) 0x01, (byte) 0x00, (byte) 0x03,
                (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x08, (byte) 0x00,
                (byte) 0x02, (byte) 0x00, (byte) 0x0E, (byte) 0x00, (byte) 0x00,
                (byte) 0x00, (byte) 0x14, (byte) 0x00, (byte) 0x04, (byte) 0x00,
                (byte) 0x08, (byte) 0x00, (byte) 0x01, (byte) 0x00, (byte) 0x04,
                (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x08, (byte) 0x00,
                (byte) 0x02, (byte) 0x00, (byte) 0x0B, (byte) 0x00, (byte) 0x00,
                (byte) 0x00, (byte) 0x24, (byte) 0x00, (byte) 0x07, (byte) 0x00,
                (byte) 0x20, (byte) 0x00, (byte) 0x01, (byte) 0x00, (byte) 0x08,
                (byte) 0x00, (byte) 0x02, (byte) 0x00, (byte) 0x03, (byte) 0x00,
                (byte) 0x00, (byte) 0x00, (byte) 0x11, (byte) 0x00, (byte) 0x01,
                (byte) 0x00, (byte) 0x6F, (byte) 0x76, (byte) 0x73, (byte) 0x5F,
                (byte) 0x64, (byte) 0x61, (byte) 0x74, (byte) 0x61, (byte) 0x70,
                (byte) 0x61, (byte) 0x74, (byte) 0x68, (byte) 0x00, (byte) 0x00,
                (byte) 0x00, (byte) 0x00
            },

            // read - time: 1341488373191
            {
                (byte) 0xB8, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x10,
                (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x02, (byte) 0x00,
                (byte) 0x00, (byte) 0x00, (byte) 0xBA, (byte) 0x03, (byte) 0x00,
                (byte) 0x00, (byte) 0x01, (byte) 0x02, (byte) 0x00, (byte) 0x00,
                (byte) 0x0E, (byte) 0x00, (byte) 0x02, (byte) 0x00, (byte) 0x6F,
                (byte) 0x76, (byte) 0x73, (byte) 0x5F, (byte) 0x76, (byte) 0x70,
                (byte) 0x6F, (byte) 0x72, (byte) 0x74, (byte) 0x00, (byte) 0x00,
                (byte) 0x00, (byte) 0x06, (byte) 0x00, (byte) 0x01, (byte) 0x00,
                (byte) 0x19, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x08,
                (byte) 0x00, (byte) 0x03, (byte) 0x00, (byte) 0x01, (byte) 0x00,
                (byte) 0x00, (byte) 0x00, (byte) 0x08, (byte) 0x00, (byte) 0x04,
                (byte) 0x00, (byte) 0x04, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                (byte) 0x08, (byte) 0x00, (byte) 0x05, (byte) 0x00, (byte) 0x64,
                (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x54, (byte) 0x00,
                (byte) 0x06, (byte) 0x00, (byte) 0x14, (byte) 0x00, (byte) 0x01,
                (byte) 0x00, (byte) 0x08, (byte) 0x00, (byte) 0x01, (byte) 0x00,
                (byte) 0x01, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x08,
                (byte) 0x00, (byte) 0x02, (byte) 0x00, (byte) 0x0B, (byte) 0x00,
                (byte) 0x00, (byte) 0x00, (byte) 0x14, (byte) 0x00, (byte) 0x02,
                (byte) 0x00, (byte) 0x08, (byte) 0x00, (byte) 0x01, (byte) 0x00,
                (byte) 0x02, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x08,
                (byte) 0x00, (byte) 0x02, (byte) 0x00, (byte) 0x0B, (byte) 0x00,
                (byte) 0x00, (byte) 0x00, (byte) 0x14, (byte) 0x00, (byte) 0x03,
                (byte) 0x00, (byte) 0x08, (byte) 0x00, (byte) 0x01, (byte) 0x00,
                (byte) 0x03, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x08,
                (byte) 0x00, (byte) 0x02, (byte) 0x00, (byte) 0x0E, (byte) 0x00,
                (byte) 0x00, (byte) 0x00, (byte) 0x14, (byte) 0x00, (byte) 0x04,
                (byte) 0x00, (byte) 0x08, (byte) 0x00, (byte) 0x01, (byte) 0x00,
                (byte) 0x04, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x08,
                (byte) 0x00, (byte) 0x02, (byte) 0x00, (byte) 0x0B, (byte) 0x00,
                (byte) 0x00, (byte) 0x00, (byte) 0x20, (byte) 0x00, (byte) 0x07,
                (byte) 0x00, (byte) 0x1C, (byte) 0x00, (byte) 0x01, (byte) 0x00,
                (byte) 0x08, (byte) 0x00, (byte) 0x02, (byte) 0x00, (byte) 0x04,
                (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x0E, (byte) 0x00,
                (byte) 0x01, (byte) 0x00, (byte) 0x6F, (byte) 0x76, (byte) 0x73,
                (byte) 0x5F, (byte) 0x76, (byte) 0x70, (byte) 0x6F, (byte) 0x72,
                (byte) 0x74, (byte) 0x00, (byte) 0x00, (byte) 0x00
            },

            // read - time: 1341488373193
            {
                (byte) 0x44, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x18,
                (byte) 0x00, (byte) 0x02, (byte) 0x00, (byte) 0x03, (byte) 0x00,
                (byte) 0x00, (byte) 0x00, (byte) 0xBA, (byte) 0x03, (byte) 0x00,
                (byte) 0x00, (byte) 0x01, (byte) 0x01, (byte) 0x00, (byte) 0x00,
                (byte) 0x08, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x08,
                (byte) 0x00, (byte) 0x01, (byte) 0x00, (byte) 0x7A, (byte) 0x7A,
                (byte) 0x7A, (byte) 0x00, (byte) 0x24, (byte) 0x00, (byte) 0x03,
                (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                (byte) 0x00, (byte) 0x00, (byte) 0x00
            },
            // read - time: 1341488373194
            {
                (byte) 0x14, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x03,
                (byte) 0x00, (byte) 0x02, (byte) 0x00, (byte) 0x03, (byte) 0x00,
                (byte) 0x00, (byte) 0x00, (byte) 0xBA, (byte) 0x03, (byte) 0x00,
                (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00
            }
        };

        Answer<Object> answer = new Answer<Object>() {
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
                    .then(answer);

        PowerMockito.when(channel.write(Matchers.<ByteBuffer>any()))
                    .then(new Answer<Object>() {
                        @Override
                        public Object answer(InvocationOnMock invocation)
                            throws Throwable {
                            connection.handleEvent(null);
                            return null;
                        }
                    });

        connection.initialize();

        Future<Set<Datapath>> future = connection.enumerateDatapaths();

        // fire the second received message
        connection.handleEvent(null);

        // validate decoding
        assertThat("The future was completed",
                   future.isDone(), is(true));

        assertThat("The future was not canceled completed",
                   future.isCancelled(), is(false));

        Set<Datapath> response = future.get();

        assertThat("We got the proper response",
                   future.get(), hasItems(new Datapath(8, "zzz")));
    }

    @Test
    public void testCreatePort() throws Exception {

        final byte[][] responses = { };

        Answer<Object> answer = new Answer<Object>() {
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
                    .then(answer);

        PowerMockito.when(channel.write(Matchers.<ByteBuffer>any()))
                    .then(new Answer<Object>() {
                        @Override
                        public Object answer(InvocationOnMock invocation)
                            throws Throwable {
                            connection.handleEvent(null);
                            return null;
                        }
                    });

        connection.initialize();

        connection.handleEvent(null);

        connection.handleEvent(null);

        Future<Port> future = connection.addPort(8, Ports.newNetDevPort("bibi"));

        connection.handleEvent(null);

        // fire the second received message
        connection.handleEvent(null);

        // validate decoding
        assertThat("The future was completed",
                   future.isDone(), is(true));

        assertThat("The future was not canceled completed",
                   future.isCancelled(), is(false));
    }
}
