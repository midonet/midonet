/*
 * Copyright 2012 Midokura Europe SARL
 */

package com.midokura.midonet.functional_test.openflow;

import java.io.IOException;
import java.nio.channels.ServerSocketChannel;
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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.util.eventloop.SelectLoop;

public class PrimaryController {
    static final Logger log = LoggerFactory.getLogger(PrimaryController.class);

    public static final int UNBUFFERED_ID = -1;

    final Lock lock = new ReentrantLock();
    final Condition connection_made = lock.newCondition();
    final Map<String, Condition> portToCondition =
            new HashMap<String, Condition>();
    private boolean connected = false;
    private Map<String, Short> portNameToNum = new HashMap<String, Short>();
    private BlockingQueue<PacketIn> pktQ =
            new ArrayBlockingQueue<PacketIn>(10);

    private ScheduledExecutorService executor;
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

    public void stop() {
        lock.lock();
        try {
            loop.shutdown();
            listenSock.close();
        } catch (IOException e) {
            log.error("Error closing listening socket.", e);
        } finally {
            lock.unlock();
        }
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
        } finally {
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
        } finally {
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
        } finally {
            lock.unlock();
        }
    }

    public void onPacketIn(int bufferId, int totalLen, short inPort,
                           byte[] data, long matchingTunnelId) {
        pktQ.add(new PacketIn(data, bufferId, totalLen, inPort,
                matchingTunnelId));
    }

    public void onPacketIn(int bufferId, int totalLen, short inPort,
                           byte[] data) {
        pktQ.add(new PacketIn(data, bufferId, totalLen, inPort, 0));
    }

}
