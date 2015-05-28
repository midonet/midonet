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
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import scala.concurrent.duration.Duration;

import org.junit.Test;

import rx.Observer;
import rx.subjects.PublishSubject;
import rx.subjects.Subject;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;


public class TestServerFrontEndUPD {

    private static final Duration TIMEOUT = Duration.apply(5, TimeUnit.SECONDS);
    private static final String MSG = "hello";
    Random rand = new Random();

    static class TestMonitor<T> implements Observer<T> {
        private final CountDownLatch latch;
        private final ArrayList<T> events = new ArrayList<>();

        public TestMonitor(int expected) {
            this.latch = new CountDownLatch(expected);
        }

        @Override public void onCompleted() {}
        @Override public void onError(Throwable e) {}
        @Override public void onNext(T v) {
            events.add(v);
            latch.countDown();
        }

        public void awaitOnNext(Duration timeout) throws InterruptedException,
                                                         TimeoutException{
            if (!latch.await(timeout.toMillis(), TimeUnit.MILLISECONDS))
                throw new TimeoutException("TestMonitor timeout");
        }

        public List<T> getOnNextEvents() {
            return events;
        }
    }

    @Test
    public void testUDPconnection() throws Exception {
        Subject<byte[], byte[]> sub = PublishSubject.create();
        SimpleChan simpleadapter = new SimpleChan(sub);
        TestMonitor<byte[]> monitor = new TestMonitor<>(1);
        sub.subscribe(monitor);
        int port = rand.nextInt(100) + 8000;
        ServerFrontEnd udpSrv = ServerFrontEnd.udp(simpleadapter, port);
        udpSrv.startAsync().awaitRunning();
        sendMSG(MSG.getBytes(), port);
        monitor.awaitOnNext(TIMEOUT);
        assertThat("msg was received", monitor.getOnNextEvents().get(0),
                   equalTo(MSG.getBytes()));
    }

    @Test
    public void testUDPLargePacket() throws Exception {
        Subject<byte[], byte[]> sub = PublishSubject.create();
        SimpleChan simpleadapter = new SimpleChan(sub);
        TestMonitor<byte[]> monitor = new TestMonitor<>(1);
        sub.subscribe(monitor);
        int port = rand.nextInt(100) + 8000;
        ServerFrontEnd udpSrv = ServerFrontEnd.udp(simpleadapter, port, 65536);
        byte[] msg = new byte[65535 - 28]; // max payload size
        rand.nextBytes(msg);
        udpSrv.startAsync().awaitRunning();
        sendMSG(msg, port);
        monitor.awaitOnNext(TIMEOUT);
        assertThat("msg was received", monitor.getOnNextEvents().get(0),
                   equalTo(msg));
    }

    private DatagramPacket sendMSG(byte[] msg, int port) throws Exception {
        DatagramSocket clientSocket = new DatagramSocket();
        InetAddress IPAddress = InetAddress.getByName("localhost");
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
