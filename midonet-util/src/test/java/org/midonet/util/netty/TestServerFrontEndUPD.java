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

import junit.framework.Assert;

import org.junit.Test;

import rx.Observer;
import rx.subjects.PublishSubject;
import rx.subjects.Subject;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;


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
        Subject<Object,Object> sub = PublishSubject.create();
        SimpleChan simpleadapter = new SimpleChan(sub);
        TestMonitor<Object> monitor = new TestMonitor<>(1);
        sub.subscribe(monitor);
        int port = rand.nextInt(100) + 8000;
        ServerFrontEnd udpSrv = ServerFrontEnd.udp(simpleadapter, port);
        udpSrv.startAsync().awaitRunning();
        sendMSG(MSG, port);
        monitor.awaitOnNext(TIMEOUT);
        Assert.assertNotNull(monitor.getOnNextEvents().get(0));
    }

    private DatagramPacket sendMSG(String msg, int port) throws Exception {
        DatagramSocket clientSocket = new DatagramSocket();
        InetAddress IPAddress = InetAddress.getByName("localhost");
        byte[] sendData = msg.getBytes();
        DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, IPAddress, port);
        clientSocket.send(sendPacket);
        clientSocket.close();
        return sendPacket;
    }

    private class SimpleChan extends SimpleChannelInboundHandler<Object> {

        private final Observer<Object> obs;

        public SimpleChan(Observer<Object> obs) { this.obs = obs; }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, Object message) throws Exception {
            obs.onNext(message);
        }

    }


}
