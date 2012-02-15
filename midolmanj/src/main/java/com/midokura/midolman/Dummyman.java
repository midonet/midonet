/*
 * Copyright 2011 Midokura KK
 */

package com.midokura.midolman;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import org.openflow.protocol.OFFlowRemoved;
import org.openflow.protocol.OFFlowRemoved.OFFlowRemovedReason;
import org.openflow.protocol.OFMatch;
import org.openflow.protocol.OFMessage;
import org.openflow.protocol.OFPhysicalPort;
import org.openflow.protocol.OFPort;
import org.openflow.protocol.OFPortStatus.OFPortReason;
import org.openflow.protocol.action.OFAction;
import org.openflow.protocol.action.OFActionOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.eventloop.SelectListener;
import com.midokura.midolman.eventloop.SelectLoop;
import com.midokura.midolman.openflow.Controller;
import com.midokura.midolman.openflow.ControllerStub;
import com.midokura.midolman.openflow.ControllerStubImpl;
import com.midokura.midolman.openflow.MidoMatch;
import com.midokura.midolman.openflow.PacketInReason;
import com.midokura.midolman.openflow.nxm.NxMatch;

public class Dummyman implements SelectListener, Controller {

    static final Logger log = LoggerFactory.getLogger(Dummyman.class);

    private ScheduledExecutorService executor;
    private ServerSocketChannel listenSock;

    private SelectLoop loop;
    
    ControllerStub controllerStub;

    private Dummyman() {}

    private void run(String[] args) throws Exception {
        executor = Executors.newScheduledThreadPool(1);
        loop = new SelectLoop(executor);

        listenSock = ServerSocketChannel.open();
        listenSock.configureBlocking(false);
        listenSock.socket().bind(new java.net.InetSocketAddress(6633));

        loop.register(listenSock, SelectionKey.OP_ACCEPT, this);

        log.debug("before doLoop which will block");
        loop.doLoop();
        log.debug("after doLoop is done");

        log.info("main finish");
    }

    @Override
    public void handleEvent(SelectionKey key) throws IOException {
        log.info("handleEvent " + key);

        try {
            SocketChannel sock = listenSock.accept();
            log.info("handleEvent acccepted connection from " +
                     sock.socket().getRemoteSocketAddress());

            sock.socket().setTcpNoDelay(true);
            sock.configureBlocking(false);

            ControllerStubImpl controllerStubImpl = new ControllerStubImpl(sock, loop, this);

            SelectionKey switchKey =
                loop.register(sock, SelectionKey.OP_READ, controllerStubImpl);

            loop.wakeup();

            controllerStubImpl.start();
        } catch (Exception e) {
            log.warn("handleEvent", e);
        }
    }

    public static void main(String[] args) {
        try {
            new Dummyman().run(args);
        } catch (Exception e) {
            log.error("main caught", e);
            System.exit(-1);
        }
    }

    @Override
    public void setControllerStub(ControllerStub controllerStub) {
        this.controllerStub = controllerStub;
    }

    @Override
    public void onConnectionMade() {
        log.info("onConnectionMade: ");
        
        controllerStub.enableNxm();
    }

    @Override
    public void onConnectionLost() {
        log.info("onConnectionLost");        
    }

    @Override
    public void onPacketIn(int bufferId, int totalLen, short inPort,
            byte[] data, long matchingTunnelId) {
        log.info("onPacketIn: bufferId={} totalLen={} inPort={} tunId={}",
                new Object[] {bufferId, totalLen, inPort, matchingTunnelId});
        /*
         * Dummyman is really dumb, so it always outputs to ofport 0.
         */
        List<OFAction> actions = new ArrayList<OFAction>();
        actions.add(new OFActionOutput((short) 2, (short) 0));
        actions.add(new OFActionOutput(OFPort.OFPP_CONTROLLER.getValue(), (short) 1024));

        MidoMatch match = new MidoMatch();
        match.loadFromPacket(data, inPort);

        controllerStub.sendFlowModAdd(
                match,
                0,
                (short) 15,
                (short) 30,
                (short) 0,
                bufferId,
                true,
                false,
                false,
                actions);
    }

    @Override
    public void onPacketIn(int bufferId, int totalLen, short inPort, byte[] data) {
        onPacketIn(bufferId, totalLen, inPort, data, 0);
    }

    @Override
    public void onFlowRemoved(OFMatch match, long cookie, short priority,
            OFFlowRemovedReason reason, int durationSeconds,
            int durationNanoseconds, short idleTimeout, long packetCount,
            long byteCount, long matchingTunnelId) {
        log.info("onFlowRemoved");
        
    }

    @Override
    public void onFlowRemoved(OFMatch match, long cookie, short priority, OFFlowRemovedReason reason, int durationSeconds, int durationNanoseconds, short idleTimeout, long packetCount, long byteCount) {
        onFlowRemoved(match, cookie, priority, reason, durationSeconds,
                durationNanoseconds, idleTimeout, packetCount, byteCount, 0);
    }

    @Override
    public void onPortStatus(OFPhysicalPort port, OFPortReason status) {
        log.info("onPortStatus: port={} status={}", port, status);
        
    }

    @Override
    public void onMessage(OFMessage m) {
        log.info("onPacketIn: message={}", m);
        
    }

}
