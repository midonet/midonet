package com.midokura.midolman.openvswitch;

import com.midokura.midolman.eventloop.SelectListener;
import junit.framework.TestCase;
import org.junit.Test;
import scala.reflect.Select;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.regex.Pattern;

/**
 * Test case for AsyncOpenvSwitchDatabaseConnection.
 */
public class TestAsyncOpenvSwitchDatabaseConnection extends TestCase {

    private class DummyReadSelectionKey extends SelectionKey {

        /**
         * The channel for which this key was created.
         */
        private SelectableChannel channel;

        public DummyReadSelectionKey(SelectableChannel channel) {
            this.channel = channel;
        }

        @Override
        public SelectableChannel channel() {
            return channel;
        }

        @Override
        public Selector selector() {
            fail();
            return null;
        }

        @Override
        public boolean isValid() {
            return true;
        }

        @Override
        public void cancel() {
            fail();
        }

        @Override
        public int interestOps() {
            return OP_READ;
        }

        @Override
        public SelectionKey interestOps(int i) {
            fail();
            return null;
        }

        @Override
        public int readyOps() {
            return OP_READ;
        }
    }

    private class PipeRpcServer implements Runnable {

        /**
         * The channel from which JSON-RPC requests are read.
         */
        private ReadableByteChannel readChannel;

        /**
         * The buffer used to read bytes from readChannel.
         */
        private ByteBuffer readBuffer;

        /**
         * The channel into which JSON-RPC replies are written.
         */
        private WritableByteChannel writeChannel;

        /**
         * The buffer used to write bytes into writeChannel.
         */
        private ByteBuffer writeBuffer;

        /**
         * The listener to notify directly after writing into the writeBuffer.
         * May be null if none is to be notified.
         */
        private SelectListener writeListener;

        /**
         * The key to pass the listener after writing into the writeBuffer.
         * May be null if none is to be notified.
         */
        private SelectionKey writeKey;

        /**
         * The JSON-RPC request expected to be received from the pipe.
         */
        private String expectedRequestRegex;

        /**
         * The request's reply.
         */
        private String reply;

        /**
         * Indicates if the test was successful.
         */
        private boolean success = false;

        public PipeRpcServer(
                ReadableByteChannel readChannel, ByteBuffer readBuffer,
                WritableByteChannel writeChannel, ByteBuffer writeBuffer,
                SelectListener writeListener, SelectionKey writeKey,
                String expectedRequestRegex, String reply) {
            this.readChannel = readChannel;
            this.readBuffer = readBuffer;
            this.writeChannel = writeChannel;
            this.writeBuffer = writeBuffer;
            this.writeListener = writeListener;
            this.writeKey = writeKey;
            this.expectedRequestRegex = expectedRequestRegex;
            this.reply = reply;
        }

        @Override
        public void run() {
            readBuffer.clear();
            try {
                readChannel.read(readBuffer);
            } catch(IOException e) {
                throw new RuntimeException(e);
            }
            readBuffer.flip();
            byte[] requestBytes = new byte[readBuffer.remaining()];
            readBuffer.get(requestBytes);
            String request = new String(requestBytes);

            assertTrue(Pattern.matches(expectedRequestRegex, request));

            writeBuffer.clear();
            writeBuffer.put(reply.getBytes());
            writeBuffer.flip();

            try {
                writeChannel.write(writeBuffer);
                if (writeListener != null && writeKey != null) {
                    writeListener.handleEvent(writeKey);
                }
            } catch(IOException e) {
                throw new RuntimeException(e);
            }

            success = true;
        }

        /**
         * Tests if this thread was successful.
         */
        public void testSuccess() {
            assertTrue(success);
        }
    }

    @Test
    public void testBridgeBuildSuccess() throws IOException {
        Pipe pipeToServer = Pipe.open();
        Pipe pipeFromServer = Pipe.open();

        AsyncOpenvSwitchDatabaseConnection conn =
                new AsyncOpenvSwitchDatabaseConnection(
                        "database-name", ByteBuffer.allocate(2048),
                        pipeToServer.sink(), ByteBuffer.allocate(2048));

        SelectionKey writeKey = new DummyReadSelectionKey(
                pipeFromServer.source());

        PipeRpcServer server = new PipeRpcServer(
                pipeToServer.source(), ByteBuffer.allocate(2048),
                pipeFromServer.sink(), ByteBuffer.allocate(2048),
                conn, writeKey,
                "\\Q{\"id\":0,\"method\":\"transact\",\"params\":[\"database-name\",{\"uuid-name\":\"row\\E([_a-f0-9]{36}+)\\Q\",\"op\":\"insert\",\"table\":\"Interface\",\"row\":{\"name\":\"bridge-name\"}},{\"uuid-name\":\"row\\E([_a-f0-9]{36}+)\\Q\",\"op\":\"insert\",\"table\":\"Port\",\"row\":{\"name\":\"bridge-name\",\"interfaces\":[\"named-uuid\",\"row\\E\\1\\Q\"]}},{\"uuid-name\":\"row\\E([_a-f0-9]{36}+)\\Q\",\"op\":\"insert\",\"table\":\"Bridge\",\"row\":{\"ports\":[\"named-uuid\",\"row\\E\\2\\Q\"],\"fail_mode\":\"secure\",\"datapath_type\":\"\",\"name\":\"bridge-name\"}},{\"mutations\":[[\"bridges\",\"insert\",[\"named-uuid\",\"row\\E\\3\\Q\"]]],\"op\":\"mutate\",\"table\":\"Open_vSwitch\"},{\"mutations\":[[\"next_cfg\",\"+=\",1]],\"op\":\"mutate\",\"table\":\"Open_vSwitch\"},{\"op\":\"comment\",\"comment\":\"added bridge bridge-name\"}]}\\E",
                "{\"result\":\"FIXME\",\"error\":null,\"id\":0}");
        // TODO: Test sending replies in several "packets", to test that
        // the JSON object parsing is correct in that case.
        Thread serverThread = new Thread(server);
        serverThread.start();

        BridgeBuilder bridgeBuilder = conn.addBridge("bridge-name");
        bridgeBuilder.failMode(BridgeFailMode.SECURE);
        bridgeBuilder.build();

        try {
            serverThread.join(2000L);
        } catch(Exception e) {
            throw new RuntimeException(e);
        }
        server.testSuccess();
    }

}
