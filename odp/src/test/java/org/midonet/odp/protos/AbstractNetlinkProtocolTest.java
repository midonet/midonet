/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.odp.protos;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.google.common.util.concurrent.ValueFuture;
import org.mockito.Matchers;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.powermock.api.mockito.PowerMockito;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static junit.framework.Assert.fail;

import org.midonet.netlink.AbstractNetlinkConnection;
import org.midonet.netlink.BufferPool;
import org.midonet.netlink.Netlink;
import org.midonet.netlink.NetlinkChannel;
import org.midonet.util.eventloop.Reactor;
import org.midonet.util.eventloop.TryCatchReactor;

public abstract class AbstractNetlinkProtocolTest {

    private static final Logger log = LoggerFactory
        .getLogger(AbstractNetlinkProtocolTest.class);

    NetlinkChannel channel = PowerMockito.mock(NetlinkChannel.class);
    BlockingQueue<ValueFuture<ByteBuffer>> listWrites;
    Reactor reactor = null;
    OvsDatapathConnection connection = null;

    protected void setConnection() throws Exception {
        connection = new OvsDatapathConnectionImpl(channel,
            new BufferPool(128, 512, 0x1000));
    }

    protected void setUp(final byte[][] responses) throws Exception {

        reactor = new TryCatchReactor("test", 1);

        Netlink.Address remote = new Netlink.Address(0);
        Netlink.Address local = new Netlink.Address(uplinkPid());

        PowerMockito.when(channel.getRemoteAddress())
                    .thenReturn(remote);

        PowerMockito.when(channel.getLocalAddress())
                    .thenReturn(local);

        // Answer that copies the next response from responses into the
        // invocation's first argument (a ByteBuffer).
        Answer<Object> playbackResponseAnswer = new Answer<Object>() {
            int position = 0;

            @Override
            public Object answer(InvocationOnMock invocation)
                throws Throwable {
                ByteBuffer result = (ByteBuffer)invocation.getArguments()[0];
                result.put(responses[position]);
                position++;
                return result.position();
            }
        };

        // Successive calls to read() will get the values in resposes in order.
        PowerMockito.when(channel.read(Matchers.<ByteBuffer>any()))
                    .then(playbackResponseAnswer);

        // Calls to write will add values (as ValueFuture<ByteBuffer>) to
        // listWrites.
        PowerMockito.when(channel.write(Matchers.<ByteBuffer>any())).then(
            new Answer<Object>() {

                @Override
                public Object answer(InvocationOnMock invocation)
                    throws Throwable {

                    ValueFuture<ByteBuffer> future = ValueFuture.create();
                    future.set(((ByteBuffer)invocation.getArguments()[0]));
                    listWrites.offer(future);

                    return null;
                }
            }
        );

        listWrites = new LinkedBlockingQueue<ValueFuture<ByteBuffer>>();

    }



    protected Future<ByteBuffer> waitWrite() throws InterruptedException {
        return listWrites.poll(100, TimeUnit.MILLISECONDS);
    }

    protected void tearDown() throws Exception {
        reactor.shutDownNow();
    }

    protected int uplinkPid() {
        return 294;
    }

    protected void exchangeMessage() throws Exception {
        exchangeMessage(1);
    }

    protected void exchangeMessage(int replyCount) throws Exception {
        try {
            waitWrite().get(100, TimeUnit.MILLISECONDS);
            while (replyCount-- > 0) {
                fireReply();
            }
        } catch (TimeoutException e) {
            fail("Waiting for the write operation timed out.");
        }
    }

    protected void fireReply() throws IOException {
        fireReply(1);
    }

    protected void fireReply(int amount) throws IOException {
        while ( amount-- > 0 ) {
            connection.handleReadEvent(null);
        }
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

    protected void initializeConnection(Future<Boolean> initialization, int messages) throws Exception {

        while (messages-- > 0) {
            exchangeMessage();
        }

        initialization.get();
    }
}
