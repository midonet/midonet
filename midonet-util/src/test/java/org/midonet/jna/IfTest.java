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

import com.sun.jna.Native;
import com.sun.jna.NativeLong;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;

import org.junit.Assert;
import org.junit.Test;

import sun.misc.Unsafe;
import sun.nio.ch.DirectBuffer;

import org.midonet.Util;
import org.midonet.packets.IPv4Addr;

public class IfTest {

    private Unsafe unsafe = Util.getUnsafe();

    @Test
    public void testIfMapWrite() {
        If.IfMap ifMap = new If.IfMap();
        ifMap.memStart = new NativeLong(0x0001020304050607L);
        ifMap.memEnd = new NativeLong(0x08090A0B0C0D0E0FL);
        ifMap.baseAddr = 0x1234;
        ifMap.irq = (byte) 0xAA;
        ifMap.dma = (byte) 0xBB;
        ifMap.port = (byte) 0xCC;
        ifMap.write();

        long ptr = Pointer.nativeValue(ifMap.getPointer());
        if (Native.LONG_SIZE == 8) {
            Assert.assertEquals(unsafe.getLong(ptr), 0x0001020304050607L);
            Assert.assertEquals(unsafe.getLong(ptr + 8), 0x08090A0B0C0D0E0FL);
            Assert.assertEquals(unsafe.getShort(ptr + 16), 0x1234);
            Assert.assertEquals(unsafe.getByte(ptr + 18), (byte) 0xAA);
            Assert.assertEquals(unsafe.getByte(ptr + 19), (byte) 0xBB);
            Assert.assertEquals(unsafe.getByte(ptr + 20), (byte) 0xCC);
        } else {
            Assert.assertEquals(unsafe.getLong(ptr), 0x04050607L);
            Assert.assertEquals(unsafe.getLong(ptr + 4), 0x0C0D0E0FL);
            Assert.assertEquals(unsafe.getShort(ptr + 8), 0x1234);
            Assert.assertEquals(unsafe.getByte(ptr + 10), (byte) 0xAA);
            Assert.assertEquals(unsafe.getByte(ptr + 11), (byte) 0xBB);
            Assert.assertEquals(unsafe.getByte(ptr + 12), (byte) 0xCC);
        }
    }

    @Test
    public void testIfMapRead() {
        ByteBuffer buffer = ByteBuffer.allocateDirect(2 * Native.LONG_SIZE + 5);
        buffer.order(ByteOrder.nativeOrder());
        DirectBuffer direct = (DirectBuffer) buffer;
        if (Native.LONG_SIZE == 8) {
            buffer.putLong(0x0001020304050607L);
            buffer.putLong(0x08090A0B0C0D0E0FL);
        } else {
            buffer.putInt(0x00010203);
            buffer.putInt(0x08090A0B);
        }
        buffer.putShort((short) 0x1234);
        buffer.put((byte) 0xAA);
        buffer.put((byte) 0xBB);
        buffer.put((byte) 0xCC);

        Pointer ptr = new Pointer(direct.address());
        If.IfMap ifMap = new If.IfMap(ptr);
        ifMap.read();

        if (Native.LONG_SIZE == 8) {
            Assert.assertEquals(ifMap.memStart,
                                new NativeLong(0x0001020304050607L));
            Assert.assertEquals(ifMap.memEnd,
                                new NativeLong(0x08090A0B0C0D0E0FL));
        } else {
            Assert.assertEquals(ifMap.memStart, new NativeLong(0x00010203L));
            Assert.assertEquals(ifMap.memEnd, new NativeLong(0x08090A0BL));
        }
        Assert.assertEquals(ifMap.baseAddr, 0x1234);
        Assert.assertEquals(ifMap.irq, (byte) 0xAA);
        Assert.assertEquals(ifMap.dma, (byte) 0xBB);
        Assert.assertEquals(ifMap.port, (byte) 0xCC);
    }

    @Test
    public void testIfMapByReference() {
        If.IfMap ifMap = new If.IfMap.ByReference();
        Assert.assertEquals(ifMap instanceof Structure.ByReference, true);
    }

    @Test
    public void testIfMapByValue() {
        If.IfMap ifMap = new If.IfMap.ByValue();
        Assert.assertEquals(ifMap instanceof Structure.ByValue, true);
    }

    @Test
    public void testIfReqWriteWithAddress() {
        If.IfReq ifReq = new If.IfReq();
        ifReq.ifrIfrn.setName("eth0");
        ifReq.ifrIfru.setAddress(IPv4Addr.apply("1.2.3.4"), (short) 0x5678);
        ifReq.write();

        long ptr = Pointer.nativeValue(ifReq.getPointer());
        Assert.assertEquals(unsafe.getByte(ptr), 'e');
        Assert.assertEquals(unsafe.getByte(ptr + 1), 't');
        Assert.assertEquals(unsafe.getByte(ptr + 2), 'h');
        Assert.assertEquals(unsafe.getByte(ptr + 3), '0');
        Assert.assertEquals(unsafe.getByte(ptr + 4), 0);
        Assert.assertEquals(unsafe.getShort(ptr + 16), Socket.AF_INET);
        Assert.assertEquals(unsafe.getShort(ptr + 18),
                            Util.hostToNetwork((short) 0x5678));
        Assert.assertEquals(unsafe.getInt(ptr + 20),
                            Util.hostToNetwork(0x01020304));
        Assert.assertEquals(ifReq.ifrIfru.ifruAddr.saFamily, Socket.AF_INET);
        Assert.assertArrayEquals(ifReq.ifrIfru.ifruAddr.saData,
                                 new byte[]{ 0x56, 0x78, 0x1, 0x2, 0x3, 0x4,
                                             0, 0, 0, 0, 0, 0, 0, 0 });
    }

    @Test
    public void testIfReqWriteWithFlags() {
        If.IfReq ifReq = new If.IfReq();
        ifReq.ifrIfrn.setName("eth0");
        ifReq.ifrIfru.setFlags((short) 0x1234);
        ifReq.write();

        long ptr = Pointer.nativeValue(ifReq.getPointer());
        Assert.assertEquals(unsafe.getByte(ptr), 'e');
        Assert.assertEquals(unsafe.getByte(ptr + 1), 't');
        Assert.assertEquals(unsafe.getByte(ptr + 2), 'h');
        Assert.assertEquals(unsafe.getByte(ptr + 3), '0');
        Assert.assertEquals(unsafe.getByte(ptr + 4), 0);
        Assert.assertEquals(unsafe.getShort(ptr + 16), 0x1234);
    }

    @Test
    public void testIfReqWriteWithMtu() {
        If.IfReq ifReq = new If.IfReq();
        ifReq.ifrIfrn.setName("eth0");
        ifReq.ifrIfru.setMtu(0x12345678);
        ifReq.write();

        long ptr = Pointer.nativeValue(ifReq.getPointer());
        Assert.assertEquals(unsafe.getByte(ptr), 'e');
        Assert.assertEquals(unsafe.getByte(ptr + 1), 't');
        Assert.assertEquals(unsafe.getByte(ptr + 2), 'h');
        Assert.assertEquals(unsafe.getByte(ptr + 3), '0');
        Assert.assertEquals(unsafe.getByte(ptr + 4), 0);
        Assert.assertEquals(unsafe.getInt(ptr + 16), 0x12345678);
    }

    @Test
    public void testIfReqWriteWithIndex() {
        If.IfReq ifReq = new If.IfReq();
        ifReq.ifrIfrn.setName("eth0");
        ifReq.ifrIfru.setIndex(0x12345678);
        ifReq.write();

        long ptr = Pointer.nativeValue(ifReq.getPointer());
        Assert.assertEquals(unsafe.getByte(ptr), 'e');
        Assert.assertEquals(unsafe.getByte(ptr + 1), 't');
        Assert.assertEquals(unsafe.getByte(ptr + 2), 'h');
        Assert.assertEquals(unsafe.getByte(ptr + 3), '0');
        Assert.assertEquals(unsafe.getByte(ptr + 4), 0);
        Assert.assertEquals(unsafe.getInt(ptr + 16), 0x12345678);
    }

    @Test
    public void testIfReqWriteWithName() {
        If.IfReq ifReq = new If.IfReq();
        ifReq.ifrIfrn.setName("eth0");
        ifReq.ifrIfru.setName("br0");
        ifReq.write();

        long ptr = Pointer.nativeValue(ifReq.getPointer());
        Assert.assertEquals(unsafe.getByte(ptr), 'e');
        Assert.assertEquals(unsafe.getByte(ptr + 1), 't');
        Assert.assertEquals(unsafe.getByte(ptr + 2), 'h');
        Assert.assertEquals(unsafe.getByte(ptr + 3), '0');
        Assert.assertEquals(unsafe.getByte(ptr + 4), 0);
        Assert.assertEquals(unsafe.getByte(ptr + 16), 'b');
        Assert.assertEquals(unsafe.getByte(ptr + 17), 'r');
        Assert.assertEquals(unsafe.getByte(ptr + 18), '0');
        Assert.assertEquals(unsafe.getByte(ptr + 19), 0);
    }

    @Test
    public void testIfReqWriteWithData() {
        If.IfReq ifReq = new If.IfReq();
        ifReq.ifrIfrn.setName("eth0");
        ifReq.ifrIfru.setData(new Pointer(0x0123456789ABCDEFL));
        ifReq.write();

        long ptr = Pointer.nativeValue(ifReq.getPointer());
        Assert.assertEquals(unsafe.getByte(ptr), 'e');
        Assert.assertEquals(unsafe.getByte(ptr + 1), 't');
        Assert.assertEquals(unsafe.getByte(ptr + 2), 'h');
        Assert.assertEquals(unsafe.getByte(ptr + 3), '0');
        Assert.assertEquals(unsafe.getByte(ptr + 4), 0);
        Assert.assertEquals(unsafe.getLong(ptr + 16), 0x0123456789ABCDEFL);
    }

    @Test
    public void testIfReqReadWithAddress() {
        byte[] name = new byte[]{ 'e', 't', 'h', '0', 0, 0, 0, 0,
                                  0, 0, 0, 0, 0, 0, 0, 0 };

        ByteBuffer buffer = ByteBuffer.allocateDirect(32);
        buffer.order(ByteOrder.nativeOrder());
        DirectBuffer direct = (DirectBuffer) buffer;
        buffer.put(name);
        buffer.putShort((short) Socket.AF_INET);
        buffer.putShort(Util.networkToHost((short) 0x5678));
        buffer.putInt(Util.networkToHost(0x01020304));

        Pointer ptr = new Pointer(direct.address());
        If.IfReq ifReq = new If.IfReq(ptr);
        ifReq.ifrIfru.setType(If.IfReq.IfrIfru.IFRU_ADDR);
        ifReq.read();

        Assert.assertArrayEquals(ifReq.ifrIfrn.ifrnName, name);
        Assert.assertEquals(ifReq.ifrIfru.ifruAddr.saFamily, Socket.AF_INET);
        Assert.assertArrayEquals(ifReq.ifrIfru.ifruAddr.saData,
                                 new byte[] { 0x56, 0x78, 0x1, 0x2, 0x3, 0x4,
                                              0, 0, 0, 0, 0, 0, 0, 0 });
    }

    @Test
    public void testIfReqWithFlags() {
        byte[] name = new byte[]{ 'e', 't', 'h', '0', 0, 0, 0, 0,
                                  0, 0, 0, 0, 0, 0, 0, 0 };

        ByteBuffer buffer = ByteBuffer.allocateDirect(32);
        buffer.order(ByteOrder.nativeOrder());
        DirectBuffer direct = (DirectBuffer) buffer;
        buffer.put(name);
        buffer.putShort((short) 0x1234);

        Pointer ptr = new Pointer(direct.address());
        If.IfReq ifReq = new If.IfReq(ptr);
        ifReq.ifrIfru.setType(If.IfReq.IfrIfru.IFRU_FLAGS);
        ifReq.read();

        Assert.assertArrayEquals(ifReq.ifrIfrn.ifrnName, name);
        Assert.assertEquals(ifReq.ifrIfru.ifruFlags, 0x1234);
    }

    @Test
    public void testIfReqWithMtu() {
        byte[] name = new byte[]{ 'e', 't', 'h', '0', 0, 0, 0, 0,
                                  0, 0, 0, 0, 0, 0, 0, 0 };

        ByteBuffer buffer = ByteBuffer.allocateDirect(32);
        buffer.order(ByteOrder.nativeOrder());
        DirectBuffer direct = (DirectBuffer) buffer;
        buffer.put(name);
        buffer.putInt(0x12345678);

        Pointer ptr = new Pointer(direct.address());
        If.IfReq ifReq = new If.IfReq(ptr);
        ifReq.ifrIfru.setType(If.IfReq.IfrIfru.IFRU_MTU);
        ifReq.read();

        Assert.assertArrayEquals(ifReq.ifrIfrn.ifrnName, name);
        Assert.assertEquals(ifReq.ifrIfru.ifruMtu, 0x12345678);
    }

    @Test
    public void testIfReqWithIndex() {
        byte[] name = new byte[]{ 'e', 't', 'h', '0', 0, 0, 0, 0,
                                  0, 0, 0, 0, 0, 0, 0, 0 };

        ByteBuffer buffer = ByteBuffer.allocateDirect(32);
        buffer.order(ByteOrder.nativeOrder());
        DirectBuffer direct = (DirectBuffer) buffer;
        buffer.put(name);
        buffer.putInt(0x12345678);

        Pointer ptr = new Pointer(direct.address());
        If.IfReq ifReq = new If.IfReq(ptr);
        ifReq.ifrIfru.setType(If.IfReq.IfrIfru.IFRU_INDEX);
        ifReq.read();

        Assert.assertArrayEquals(ifReq.ifrIfrn.ifrnName, name);
        Assert.assertEquals(ifReq.ifrIfru.ifruIndex, 0x12345678);
    }

    @Test
    public void testIfReqWithName() {
        byte[] name = new byte[]{ 'e', 't', 'h', '0', 0, 0, 0, 0,
                                  0, 0, 0, 0, 0, 0, 0, 0 };

        ByteBuffer buffer = ByteBuffer.allocateDirect(32);
        buffer.order(ByteOrder.nativeOrder());
        DirectBuffer direct = (DirectBuffer) buffer;
        buffer.put(name);
        buffer.put(name);

        Pointer ptr = new Pointer(direct.address());
        If.IfReq ifReq = new If.IfReq(ptr);
        ifReq.ifrIfru.setType(If.IfReq.IfrIfru.IFRU_NAME);
        ifReq.read();

        Assert.assertArrayEquals(ifReq.ifrIfrn.ifrnName, name);
        Assert.assertArrayEquals(ifReq.ifrIfru.ifruName, name);
    }

    @Test
    public void testIfReqWithData() {
        byte[] name = new byte[]{ 'e', 't', 'h', '0', 0, 0, 0, 0,
                                  0, 0, 0, 0, 0, 0, 0, 0 };

        ByteBuffer buffer = ByteBuffer.allocateDirect(32);
        buffer.order(ByteOrder.nativeOrder());
        DirectBuffer direct = (DirectBuffer) buffer;
        buffer.put(name);
        buffer.putLong(0x0123456789ABCDEFL);

        Pointer ptr = new Pointer(direct.address());
        If.IfReq ifReq = new If.IfReq(ptr);
        ifReq.ifrIfru.setType(If.IfReq.IfrIfru.IFRU_DATA);
        ifReq.read();

        Assert.assertArrayEquals(ifReq.ifrIfrn.ifrnName, name);
        Assert.assertEquals(Pointer.nativeValue(ifReq.ifrIfru.ifruData),
                            0x0123456789ABCDEFL);
    }

    @Test
    public void testIfReqByReference() {
        If.IfReq ifReq = new If.IfReq.ByReference();
        Assert.assertEquals(ifReq instanceof Structure.ByReference, true);
    }

}
