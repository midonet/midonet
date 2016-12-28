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

public class EthtoolTest {

    private Unsafe unsafe = Util.getUnsafe();

    @Test
    public void testEthtoolCmdWrite() {
        Ethtool.EthtoolCmd ethtoolCmd = new Ethtool.EthtoolCmd();
        ethtoolCmd.cmd = 0x01234567;
        ethtoolCmd.supported = 0x01234567;
        ethtoolCmd.advertising = 0x01234567;
        ethtoolCmd.speed = (short) 0xABCD;
        ethtoolCmd.duplex = (byte) 0xAA;
        ethtoolCmd.port = (byte) 0xBB;
        ethtoolCmd.phyAddress = (byte) 0xCC;
        ethtoolCmd.transceiver = (byte) 0xDD;
        ethtoolCmd.autoneg = (byte) 0xEE;
        ethtoolCmd.mdioSupport = (byte) 0xFF;
        ethtoolCmd.maxtxpkt = 0x01234567;
        ethtoolCmd.maxrxpkt = 0x01234567;
        ethtoolCmd.speedHi = (short) 0x1234;
        ethtoolCmd.ethTpMdix = (byte) 0x11;
        ethtoolCmd.ethTpMdixCtrl = (byte) 0x22;
        ethtoolCmd.lpAdvertising = 0x01234567;
        ethtoolCmd.write();

        long ptr = Pointer.nativeValue(ethtoolCmd.getPointer());
        Assert.assertEquals(unsafe.getInt(ptr), 0x01234567);
        Assert.assertEquals(unsafe.getInt(ptr + 4), 0x01234567);
        Assert.assertEquals(unsafe.getInt(ptr + 8), 0x01234567);
        Assert.assertEquals(unsafe.getShort(ptr + 12), (short) 0xABCD);
        Assert.assertEquals(unsafe.getByte(ptr + 14), (byte) 0xAA);
        Assert.assertEquals(unsafe.getByte(ptr + 15), (byte) 0xBB);
        Assert.assertEquals(unsafe.getByte(ptr + 16), (byte) 0xCC);
        Assert.assertEquals(unsafe.getByte(ptr + 17), (byte) 0xDD);
        Assert.assertEquals(unsafe.getByte(ptr + 18), (byte) 0xEE);
        Assert.assertEquals(unsafe.getByte(ptr + 19), (byte) 0xFF);
        Assert.assertEquals(unsafe.getInt(ptr + 20), 0x01234567);
        Assert.assertEquals(unsafe.getInt(ptr + 24), 0x01234567);
        Assert.assertEquals(unsafe.getShort(ptr + 28), (short) 0x1234);
        Assert.assertEquals(unsafe.getByte(ptr + 30), (byte) 0x11);
        Assert.assertEquals(unsafe.getByte(ptr + 31), (byte) 0x22);
        Assert.assertEquals(unsafe.getInt(ptr + 32), 0x01234567);
    }

    @Test
    public void testEthtoolCmdRead() {
        ByteBuffer buffer = ByteBuffer.allocateDirect(36);
        buffer.order(ByteOrder.nativeOrder());
        DirectBuffer direct = (DirectBuffer) buffer;
        buffer.putInt(0x01234567);
        buffer.putInt(0x01234567);
        buffer.putInt(0x01234567);
        buffer.putShort((short) 0xABCD);
        buffer.put(new byte[]{ (byte) 0xAA, (byte) 0xBB, (byte) 0xCC,
                               (byte) 0xDD, (byte) 0xEE, (byte) 0xFF });
        buffer.putInt(0x01234567);
        buffer.putInt(0x01234567);
        buffer.putShort((short) 0x1234);
        buffer.put(new byte[]{ (byte) 0x11, (byte) 0x22 });
        buffer.putInt(0x01234567);

        Pointer ptr = new Pointer(direct.address());
        Ethtool.EthtoolCmd ethtoolCmd = new Ethtool.EthtoolCmd(ptr);
        ethtoolCmd.read();

        Assert.assertEquals(ethtoolCmd.cmd, 0x01234567);
        Assert.assertEquals(ethtoolCmd.supported, 0x01234567);
        Assert.assertEquals(ethtoolCmd.advertising, 0x01234567);
        Assert.assertEquals(ethtoolCmd.speed, (short) 0xABCD);
        Assert.assertEquals(ethtoolCmd.duplex, (byte) 0xAA);
        Assert.assertEquals(ethtoolCmd.port, (byte) 0xBB);
        Assert.assertEquals(ethtoolCmd.phyAddress, (byte) 0xCC);
        Assert.assertEquals(ethtoolCmd.transceiver, (byte) 0xDD);
        Assert.assertEquals(ethtoolCmd.autoneg, (byte) 0xEE);
        Assert.assertEquals(ethtoolCmd.mdioSupport, (byte) 0xFF);
        Assert.assertEquals(ethtoolCmd.maxtxpkt, 0x01234567);
        Assert.assertEquals(ethtoolCmd.maxrxpkt, 0x01234567);
        Assert.assertEquals(ethtoolCmd.speedHi, (short) 0x1234);
        Assert.assertEquals(ethtoolCmd.ethTpMdix, (byte) 0x11);
        Assert.assertEquals(ethtoolCmd.ethTpMdixCtrl, (byte) 0x22);
        Assert.assertEquals(ethtoolCmd.lpAdvertising, 0x01234567);
    }

    @Test
    public void testEthtoolCmdByReference() {
        Ethtool.EthtoolCmd ethtoolCmd = new Ethtool.EthtoolCmd.ByReference();
        Assert.assertEquals(ethtoolCmd instanceof Structure.ByReference, true);
    }

    @Test
    public void testEthtoolCmdByValue() {
        Ethtool.EthtoolCmd ethtoolCmd = new Ethtool.EthtoolCmd.ByValue();
        Assert.assertEquals(ethtoolCmd instanceof Structure.ByValue, true);
    }

    @Test
    public void testEthtoolValueWrite() {
        Ethtool.EthtoolValue ethtoolValue = new Ethtool.EthtoolValue();
        ethtoolValue.cmd = 0x01234567;
        ethtoolValue.data = 0x89ABCDEF;
        ethtoolValue.write();

        long ptr = Pointer.nativeValue(ethtoolValue.getPointer());
        Assert.assertEquals(unsafe.getInt(ptr), 0x01234567);
        Assert.assertEquals(unsafe.getInt(ptr + 4), 0x89ABCDEF);
    }

    @Test
    public void testEthtoolValueRead() {
        ByteBuffer buffer = ByteBuffer.allocateDirect(8);
        buffer.order(ByteOrder.nativeOrder());
        DirectBuffer direct = (DirectBuffer) buffer;
        buffer.putInt(0x01234567);
        buffer.putInt(0x89ABCDEF);

        Pointer ptr = new Pointer(direct.address());
        Ethtool.EthtoolValue ethtoolValue = new Ethtool.EthtoolValue(ptr);
        ethtoolValue.read();

        Assert.assertEquals(ethtoolValue.cmd, 0x01234567);
        Assert.assertEquals(ethtoolValue.data, 0x89ABCDEF);
    }

    @Test
    public void testEthtoolValueByReference() {
        Ethtool.EthtoolValue ethtoolValue = new Ethtool.EthtoolValue.ByReference();
        Assert.assertEquals(ethtoolValue instanceof Structure.ByReference, true);
    }

    @Test
    public void testEthtoolValueByValue() {
        Ethtool.EthtoolValue ethtoolValue = new Ethtool.EthtoolValue.ByValue();
        Assert.assertEquals(ethtoolValue instanceof Structure.ByValue, true);
    }
}
