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

public class SocketTest {

    private Unsafe unsafe = Util.getUnsafe();

    @Test
    public void testSockAddrWrite() {
        byte[] data = new byte[] { 0x0, 0x1, 0x2, 0x3, 0x4, 0x5, 0x6, 0x7,
                                   0x8, 0x9, 0xA, 0xB, 0xC, 0xD };

        Socket.SockAddr sockAddr = new Socket.SockAddr();
        sockAddr.saFamily = 0x1234;
        System.arraycopy(data, 0, sockAddr.saData, 0, 14);
        sockAddr.write();

        long ptr = Pointer.nativeValue(sockAddr.getPointer());
        Assert.assertEquals(unsafe.getShort(ptr), 0x1234);
        for (int i = 0; i < 14; i++) {
            Assert.assertEquals(unsafe.getByte(ptr + i + 2), data[i]);
        }
    }

    @Test
    public void testSockAddrRead() {
        byte[] data = new byte[] { 0x0, 0x1, 0x2, 0x3, 0x4, 0x5, 0x6, 0x7,
                                   0x8, 0x9, 0xA, 0xB, 0xC, 0xD };

        ByteBuffer buffer = ByteBuffer.allocateDirect(16);
        buffer.order(ByteOrder.nativeOrder());
        DirectBuffer direct = (DirectBuffer) buffer;
        buffer.putShort((short)0x1234);
        buffer.put(data);

        Pointer ptr = new Pointer(direct.address());
        Socket.SockAddr sockAddr = new Socket.SockAddr(ptr);
        sockAddr.read();

        Assert.assertEquals(sockAddr.saFamily, 0x1234);
        Assert.assertArrayEquals(sockAddr.saData, data);
    }

    @Test
    public void testSockAddrByReference() {
        Socket.SockAddr sockAddr = new Socket.SockAddr.ByReference();
        Assert.assertEquals(sockAddr instanceof Structure.ByReference, true);
    }

    @Test
    public void testSockAddrByValue() {
        Socket.SockAddr sockAddr = new Socket.SockAddr.ByValue();
        Assert.assertEquals(sockAddr instanceof Structure.ByValue, true);
    }

}
