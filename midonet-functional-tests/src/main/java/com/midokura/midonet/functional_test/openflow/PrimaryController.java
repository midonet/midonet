/*
 * Copyright 2012 Midokura Europe SARL
 */

package com.midokura.midonet.functional_test.openflow;

import java.io.IOException;
import java.nio.channels.SelectionKey;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.openflow.protocol.OFFlowRemoved;
import org.openflow.protocol.OFMatch;
import org.openflow.protocol.OFMessage;
import org.openflow.protocol.OFPhysicalPort;
import org.openflow.protocol.OFPortStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.eventloop.SelectListener;
import com.midokura.midolman.eventloop.SelectLoop;
import com.midokura.midolman.openflow.Controller;
import com.midokura.midolman.openflow.ControllerStub;
import com.midokura.midolman.openflow.ControllerStubImpl;

public class PrimaryController implements Controller, SelectListener {
    static final Logger log = LoggerFactory.getLogger(PrimaryController.class);

    public static final int UNBUFFERED_ID = ControllerStub.UNBUFFERED_ID;

    final Lock lock = new ReentrantLock();
    final Condition connection_made = lock.newCondition();
    final Map<String, Condition> portToCondition =
            new HashMap<String, Condition>();
    private boolean connected = false;
    private Map<String, Short> portNameToNum = new HashMap<String, Short>();
    private BlockingQueue<PacketIn> pktQ =
            new ArrayBlockingQueue<PacketIn>(10);

    private ScheduledExecutorService executor;
    private ControllerStub controllerStub;
    private SelectLoop loop;
    private ServerSocketChannel listenSock;
    private Thread myThread;
    private Protocol proto;

    public enum Protocol {
        NXM, OF1_0;
    }

    public PrimaryController(int listenPort) throws IOException {
        this(listenPort, Protocol.OF1_0);
    }

    public PrimaryController(int listenPort, Protocol proto) throws IOException {
        if (null == proto)
            throw new IllegalArgumentException("This constructor requires " +
                    "specifying a Protocol (NXM or OF1_0).");
        this.proto = proto;
        listenSock = ServerSocketChannel.open();
        listenSock.configureBlocking(false);
        listenSock.socket().bind(
                new java.net.InetSocketAddress(listenPort));

        executor = Executors.newScheduledThreadPool(1);
        loop = new SelectLoop(executor);
        loop.register(listenSock, SelectionKey.OP_ACCEPT, this);
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

    public class PacketIn {
        public byte[] packet;
        public int bufferId;
        public int totalLen;
        public short inPort;
        public long tunnelId;

        public PacketIn(byte[] packet, int bufferId, int totalLen, short inPort,
                        long tunnelId) {
            this.packet = packet;
            this.bufferId = bufferId;
            this.totalLen = totalLen;
            this.inPort = inPort;
            this.tunnelId = tunnelId;
        }
    }

    public boolean waitForBridge(String name)
            throws InterruptedException {
        lock.lock();
        try {
            if (!connected)
                connection_made.await(2, TimeUnit.SECONDS);
                return connected;
        }
        finally {
            lock.unlock();
        }
    }

    public boolean waitForPort(String portName)
            throws InterruptedException {
        lock.lock();
        try {
            if (portNameToNum.containsKey(portName))
                return true;
            Condition c = portToCondition.get(portName);
            if (null == c) {
                c = lock.newCondition();
                portToCondition.put(portName, c);
            }
            c.await(2, TimeUnit.SECONDS);
            return portNameToNum.containsKey(portName);
        }
        finally {
            lock.unlock();
        }
    }

    public PacketIn getNextPacket() throws InterruptedException {
        return pktQ.poll(2, TimeUnit.SECONDS);
    }

    public short getPortNum(String name) {
        lock.lock();
        try {
            Short num = portNameToNum.get(name);
            if (null == num)
                return -1;
            return num;
        }
        finally {
            lock.unlock();
        }
    }

    public ControllerStub getStub() {
        lock.lock();
        try {
            return controllerStub;
        }
        finally {
            lock.unlock();
        }
    }

    /*
     * SelectListener methods.
     */

    @Override
    public void handleEvent(SelectionKey key) throws IOException {
        log.info("handleEvent " + key);
        lock.lock();
        try {
            SocketChannel sock = listenSock.accept();
            log.info("handleEvent accepted connection from " +
                    sock.socket().getRemoteSocketAddress());

            sock.socket().setTcpNoDelay(true);
            sock.configureBlocking(false);

            ControllerStubImpl controllerStubImpl =
                    new ControllerStubImpl(sock, loop, this);

            SelectionKey switchKey =
                    loop.register(sock, SelectionKey.OP_READ, controllerStubImpl);

            loop.wakeup();

            controllerStubImpl.start();
        } catch (Exception e) {
            log.warn("handleEvent", e);
        }
        finally {
            lock.unlock();
        }
    }

    /*
    * Controller methods.
    */

    @Override
    public void setControllerStub(ControllerStub controllerStub) {
        lock.lock();
        try {
            this.controllerStub = controllerStub;
        }
        finally {
            lock.unlock();
        }
    }

    @Override
    public void onConnectionMade() {
        log.info("onConnectionMade");
        // TODO: wait for ack before considering the switch 'connected'?
        // Don't hold the lock while calling the controllerStub's synchronized
        // methods. Those methods call our controller callbacks that may
        // try to acquire the lock - resulting in deadlock.
        if (Protocol.NXM.equals(proto))
            controllerStub.enableNxm();

        lock.lock();
        try {
            connected = true;
            connection_made.signalAll();
        }
        finally {
            lock.unlock();
        }
    }

    @Override
    public void onConnectionLost() {
        log.info("onConnectionLost");
    }

    @Override
    public void onPacketIn(int bufferId, int totalLen, short inPort,
                           byte[] data, long matchingTunnelId) {
        pktQ.add(new PacketIn(data, bufferId, totalLen, inPort,
                matchingTunnelId));
    }

    @Override
    public void onPacketIn(int bufferId, int totalLen, short inPort,
                           byte[] data) {
        pktQ.add(new PacketIn(data, bufferId, totalLen, inPort, 0));
    }

    @Override
    public void onFlowRemoved(OFMatch match, long cookie, short priority,
            OFFlowRemoved.OFFlowRemovedReason reason, int durationSeconds,
            int durationNanoseconds, short idleTimeout, long packetCount,
            long byteCount, long matchingTunnelId) {
        log.info("onFlowRemoved");
    }

    @Override
    public void onFlowRemoved(OFMatch match, long cookie, short priority,
            OFFlowRemoved.OFFlowRemovedReason reason, int durationSeconds,
            int durationNanoseconds, short idleTimeout, long packetCount,
            long byteCount) {
        log.info("onFlowRemoved");
    }

    @Override
    public void onPortStatus(OFPhysicalPort portDesc,
                             OFPortStatus.OFPortReason reason) {
        short portNum = portDesc.getPortNumber();
        String name = portDesc.getName();
        log.info("onPortStatus: num:{} name:{} reason:{}",
                new Object[] { portNum & 0xffff, name, reason });
        if (reason.equals(OFPortStatus.OFPortReason.OFPPR_DELETE))
            return;

        lock.lock();
        try {
            portNameToNum.put(name, portNum);
            Condition c = portToCondition.get(name);
            if (null != c)
                c.signalAll();
        }
        finally {
            lock.unlock();
        }
    }

    @Override
    public void onMessage(OFMessage m) {
        log.info("onMessage {}", m);
    }
}
