package com.midokura.midonet.smoketest.openflow;

import java.io.IOException;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import org.openflow.protocol.OFFlowRemoved.OFFlowRemovedReason;
import org.openflow.protocol.OFMatch;
import org.openflow.protocol.OFMessage;
import org.openflow.protocol.OFPhysicalPort;
import org.openflow.protocol.OFPortStatus.OFPortReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.eventloop.SelectListener;
import com.midokura.midolman.eventloop.SelectLoop;
import com.midokura.midolman.openflow.Controller;
import com.midokura.midolman.openflow.ControllerStub;
import com.midokura.midolman.openflow.ControllerStubImpl;

public class ServiceController implements Controller, SelectListener {

    static final Logger log = LoggerFactory.getLogger(ServiceController.class);

    private ScheduledExecutorService executor;
    private ControllerStub controllerStub;
    private SelectLoop loop;
    private SocketChannel client;
    Thread myThread;

    public ServiceController(String host, int port) throws IOException {
        client = SocketChannel.open();
        client.configureBlocking(true);
        boolean connected = client.connect(new java.net.InetSocketAddress(host,
                port));
        if (connected)
            log.info("connected immediately...");
        executor = Executors.newScheduledThreadPool(1);
        loop = new SelectLoop(executor);
        loop.register(client, SelectionKey.OP_CONNECT, this);
        myThread = new Thread() {
            @Override
            public void run() {
                log.debug("before doLoop which will block");
                try {
                    loop.doLoop();
                } catch (IOException e) {
                    log.error("exited doLoop because of exception", e);
                }
                log.debug("after doLoop is done");
            }
        };
        myThread.start();
    }

    @Override
    public void handleEvent(SelectionKey key) throws IOException {
        log.info("handleEvent " + key);

        if (key.isConnectable())
            log.info("Can connect...");
        if (key.channel().equals(client))
            log.info("key's channel same as client.");
        else
            log.info("key's channel different from client");
        if (client.isConnectionPending())
            client.finishConnect();
        client.socket().setTcpNoDelay(true);
        ControllerStubImpl controllerStubImpl = new ControllerStubImpl(client,
                loop, this);
        SelectionKey switchKey = loop.register(client, SelectionKey.OP_READ,
                controllerStubImpl);
        loop.wakeup();
        controllerStubImpl.start();
    }

    @Override
    public void setControllerStub(ControllerStub controllerStub) {
        this.controllerStub = controllerStub;
    }

    @Override
    public void onConnectionMade() {
        log.info("onConnectionMade");
    }

    @Override
    public void onConnectionLost() {
        log.info("onConnectionLost");
    }

    @Override
    public void onPacketIn(int bufferId, int totalLen, short inPort, byte[] data) {
        log.info("onPacketIn");
    }

    @Override
    public void onFlowRemoved(OFMatch match, long cookie, short priority,
            OFFlowRemovedReason reason, int durationSeconds,
            int durationNanoseconds, short idleTimeout, long packetCount,
            long byteCount) {
        log.info("onFlowRemoved");
    }

    @Override
    public void onPortStatus(OFPhysicalPort port, OFPortReason status) {
        log.info("onPortStatus");
    }

    @Override
    public void onMessage(OFMessage m) {
        log.info("onMessage {}", m);
    }

}
