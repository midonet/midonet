/*
 * Copyright 2016 Midokura SARL
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

package org.midonet.jna;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import com.sun.jna.Pointer;
import com.sun.jna.Structure;

import org.junit.Assert;
import org.junit.Test;

import sun.misc.Unsafe;
import sun.nio.ch.DirectBuffer;

import org.midonet.Util;

public class InTest {

    private Unsafe unsafe = Util.getUnsafe();

    @Test
    public void testInAddrWrite() {
        In.InAddr inAddr = new In.InAddr();
        inAddr.sAddr = 0x12345678;
        inAddr.write();

        long ptr = Pointer.nativeValue(inAddr.getPointer());
        Assert.assertEquals(unsafe.getInt(ptr), 0x12345678);
    }

    @Test
    public void testInAddrRead() {
        ByteBuffer buffer = ByteBuffer.allocateDirect(4);
        buffer.order(ByteOrder.nativeOrder());
        DirectBuffer direct = (DirectBuffer) buffer;
        buffer.putInt(0x12345678);

        Pointer ptr = new Pointer(direct.address());
        In.InAddr inAddr = new In.InAddr(ptr);
        inAddr.read();

        Assert.assertEquals(inAddr.sAddr, 0x12345678);
    }

    @Test
    public void testInAddrByReference() {
        In.InAddr inAddr = new In.InAddr.ByReference();
        Assert.assertEquals(inAddr instanceof Structure.ByReference, true);
    }

    @Test
    public void testInAddrByValue() {
        In.InAddr inAddr = new In.InAddr.ByValue();
        Assert.assertEquals(inAddr instanceof Structure.ByValue, true);
    }

    @Test
    public void testSockAddrInWrite() {
        In.SockAddrIn sockAddrIn = new In.SockAddrIn();
        sockAddrIn.sinFamily = 0x1234;
        sockAddrIn.sinPort = 0x5678;
        sockAddrIn.sinAddr.sAddr = 0x9ABCDEF0;
        sockAddrIn.write();

        long ptr = Pointer.nativeValue(sockAddrIn.getPointer());
        Assert.assertEquals(unsafe.getShort(ptr), 0x1234);
        Assert.assertEquals(unsafe.getShort(ptr + 2), 0x5678);
        Assert.assertEquals(unsafe.getInt(ptr + 4), 0x9ABCDEF0);
        Assert.assertEquals(sockAddrIn.size(), 16);
    }

    @Test
    public void testSockAddrInRead() {
        ByteBuffer buffer = ByteBuffer.allocateDirect(16);
        buffer.order(ByteOrder.nativeOrder());
        DirectBuffer direct = (DirectBuffer) buffer;
        buffer.putShort((short) 0x1234);
        buffer.putShort((short) 0x5678);
        buffer.putInt(0x9ABCDEF0);

        Pointer ptr = new Pointer(direct.address());
        In.SockAddrIn sockAddrIn = new In.SockAddrIn(ptr);
        sockAddrIn.read();

        Assert.assertEquals(sockAddrIn.sinFamily, 0x1234);
        Assert.assertEquals(sockAddrIn.sinPort, 0x5678);
        Assert.assertEquals(sockAddrIn.sinAddr.sAddr, 0x9ABCDEF0);
    }

    @Test
    public void testSockAddrInByReference() {
        In.SockAddrIn sockAddrIn = new In.SockAddrIn.ByReference();
        Assert.assertEquals(sockAddrIn instanceof Structure.ByReference, true);
    }

    @Test
    public void testSockAddrInByValue() {
        In.SockAddrIn sockAddrIn = new In.SockAddrIn.ByValue();
        Assert.assertEquals(sockAddrIn instanceof Structure.ByValue, true);
    }

}
