/*
 * Copyright 2015 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.midonet.util.netty;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import scala.concurrent.duration.Duration;

import org.apache.commons.lang3.tuple.Pair;
import org.junit.Assume;
import org.junit.Test;

import rx.Observer;
import rx.subjects.PublishSubject;
import rx.subjects.Subject;

import org.midonet.util.reactivex.TestAwaitableObserver;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.SimpleChannelInboundHandler;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;


public class TestServerFrontEndUPD {

    private static final Duration TIMEOUT = Duration.apply(5, TimeUnit.SECONDS);
    private static final String MSG = "hello";
    private static final int RETRIES = 5;
    Random rand = new Random();

    private Pair<ServerFrontEnd, Integer>
    connect(ChannelInboundHandlerAdapter adapter, Integer bufSize)
        throws Exception {
        return connect(adapter, null, bufSize);
    }

    private Pair<ServerFrontEnd, Integer>
    connect(ChannelInboundHandlerAdapter adapter, String bindAddress,
            Integer bufSize)
        throws Exception {
        System.out.println("Bind address: " + bindAddress);
        int port;
        ServerFrontEnd srv = null;
        int chances = RETRIES;
        boolean ready = false;
        do {
            chances --;
            port = rand.nextInt(10000) + 40000;
            try {
                if (bufSize == null)
                    if (bindAddress == null)
                        srv = ServerFrontEnd.udp(adapter, port);
                    else
                        srv = ServerFrontEnd.udp(
                            adapter, new InetSocketAddress(bindAddress, port));
                else
                    if (bindAddress == null)
                        srv = ServerFrontEnd.udp(adapter, port, bufSize);
                    else
                        srv = ServerFrontEnd.udp(
                            adapter, new InetSocketAddress(bindAddress, port),
                            bufSize);
                srv.startAsync().awaitRunning();
                ready = true;
            } catch (IllegalStateException e) {
                if (chances <= 0)
                    throw e;
            }
        } while (!ready);
        return Pair.of(srv, port);
    }

    private String getHostIP() throws Exception {
        Enumeration<NetworkInterface> interfaces =
            NetworkInterface.getNetworkInterfaces();

        while (interfaces.hasMoreElements()) {
            NetworkInterface intrfc = interfaces.nextElement();

            if (intrfc.isUp() && !intrfc.isLoopback()) {
                Optional<String> validAddress =
                    intrfc.getInterfaceAddresses().stream()
                        .map(InterfaceAddress::getAddress)
                        .filter(a -> a instanceof Inet4Address)
                        .map(InetAddress::getHostAddress).findFirst();

                if (validAddress.isPresent()) {
                    return validAddress.get();
                }
            }
        }

        return null;
    }

    @Test
    public void testUDPconnection() throws Exception {
        Subject<byte[], byte[]> sub = PublishSubject.create();
        SimpleChan simpleadapter = new SimpleChan(sub);
        TestAwaitableObserver<byte[]> monitor = new TestAwaitableObserver<>();
        sub.subscribe(monitor);
        Pair<ServerFrontEnd, Integer> cnxn = connect(simpleadapter, null);
        ServerFrontEnd udpSrv = cnxn.getLeft();
        int port = cnxn.getRight();
        sendMSG(MSG.getBytes(), port);
        monitor.awaitOnNext(1, TIMEOUT);
        assertThat("msg was received", monitor.getOnNextEvents().get(0),
                   equalTo(MSG.getBytes()));
        udpSrv.stopAsync().awaitTerminated();
    }

    @Test
    public void testUDPconnectionBindAddress() throws Exception {
        Subject<byte[], byte[]> sub = PublishSubject.create();
        SimpleChan simpleadapter = new SimpleChan(sub);
        TestAwaitableObserver<byte[]> monitor = new TestAwaitableObserver<>();
        sub.subscribe(monitor);
        String hostIP = getHostIP();
        // Skip the test if we couldn't find a non-localhost address
        Assume.assumeNotNull(hostIP);
        Pair<ServerFrontEnd, Integer> cnxn = connect(simpleadapter, hostIP,
                                                     null);
        ServerFrontEnd udpSrv = cnxn.getLeft();
        int port = cnxn.getRight();
        sendMSG(MSG.getBytes(), hostIP, port);
        monitor.awaitOnNext(1, TIMEOUT);
        assertThat("msg was received", monitor.getOnNextEvents().get(0),
                   equalTo(MSG.getBytes()));
        monitor.reset();
        // Send to localhost. Should not be received because server is not
        // binded on localhost
        sendMSG(MSG.getBytes(), port);
        try {
            monitor.awaitOnNext(1, TIMEOUT);
            assertThat("msg received when it shouldn't have been", false);
        } catch (TimeoutException e) {
            assertThat("msg was not received", true);
        }

        udpSrv.stopAsync().awaitTerminated();
    }

    @Test
    public void testUDPLargePacket() throws Exception {
        Subject<byte[], byte[]> sub = PublishSubject.create();
        SimpleChan simpleadapter = new SimpleChan(sub);
        TestAwaitableObserver<byte[]> monitor = new TestAwaitableObserver<>();
        sub.subscribe(monitor);
        Pair<ServerFrontEnd, Integer> cnxn = connect(simpleadapter, 65535);
        ServerFrontEnd udpSrv = cnxn.getLeft();
        int port = cnxn.getRight();
        byte[] msg = new byte[65535 - 28]; // max payload size
        rand.nextBytes(msg);
        sendMSG(msg, port);
        monitor.awaitOnNext(1, TIMEOUT);
        assertThat("msg was received", monitor.getOnNextEvents().get(0),
                   equalTo(msg));
        udpSrv.stopAsync().awaitTerminated();
    }

    @Test
    public void testUDPLargePacketBindAddress() throws Exception {
        Subject<byte[], byte[]> sub = PublishSubject.create();
        SimpleChan simpleadapter = new SimpleChan(sub);
        TestAwaitableObserver<byte[]> monitor = new TestAwaitableObserver<>();
        sub.subscribe(monitor);
        String hostIP = getHostIP();
        // Skip the test if we couldn't find a non-localhost address
        Assume.assumeNotNull(hostIP);
        Pair<ServerFrontEnd, Integer> cnxn = connect(simpleadapter, hostIP,
                                                     65535);
        ServerFrontEnd udpSrv = cnxn.getLeft();
        int port = cnxn.getRight();
        byte[] msg = new byte[65535 - 28]; // max payload size
        rand.nextBytes(msg);
        sendMSG(msg, hostIP, port);
        monitor.awaitOnNext(1, TIMEOUT);
        assertThat("msg was received", monitor.getOnNextEvents().get(0),
                   equalTo(msg));
        monitor.reset();
        // Send to localhost. Should not be received because server is not
        // binded on localhost
        sendMSG(MSG.getBytes(), port);
        try {
            monitor.awaitOnNext(1, TIMEOUT);
            assertThat("msg received when it shouldn't have been", false);
        } catch (TimeoutException e) {
            assertThat("msg was not received", true);
        }
        udpSrv.stopAsync().awaitTerminated();
    }

    private DatagramPacket sendMSG(byte[] msg, int port) throws Exception {
        return sendMSG(msg, "localhost", port);
    }

    private DatagramPacket sendMSG(byte[] msg, String host, int port)
        throws Exception {
        DatagramSocket clientSocket = new DatagramSocket();
        InetAddress IPAddress = InetAddress.getByName(host);
        DatagramPacket sendPacket =
            new DatagramPacket(msg, msg.length, IPAddress, port);
        clientSocket.send(sendPacket);
        clientSocket.close();
        return sendPacket;
    }

    private class SimpleChan
        extends SimpleChannelInboundHandler<io.netty.channel.socket.DatagramPacket> {

        private final Observer<byte[]> obs;
        public SimpleChan(Observer<byte[]> obs) {
            this.obs = obs;
        }

        @Override
        protected void channelRead0(
            ChannelHandlerContext ctx,
            io.netty.channel.socket.DatagramPacket message) throws Exception {
            byte[] bytes = new byte[message.content().readableBytes()];
            message.content().readBytes(bytes);
            obs.onNext(bytes);
        }
    }
}
