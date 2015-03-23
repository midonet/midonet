/**
*    Copyright 2011, Big Switch Networks, Inc.
*    Originally created by David Erickson, Stanford University
*
*    Licensed under the Apache License, Version 2.0 (the "License"); you may
*    not use this file except in compliance with the License. You may obtain
*    a copy of the License at
*
*         http://www.apache.org/licenses/LICENSE-2.0
*
*    Unless required by applicable law or agreed to in writing, software
*    distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
*    WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
*    License for the specific language governing permissions and limitations
*    under the License.
**/

package org.midonet.packets;

import java.nio.ByteBuffer;
import java.util.Arrays;

public class BPDU extends BasePacket {

    public final static short ETHERTYPE = 0x0100;

    public final static short HW_TYPE_ETHERNET = 0x1;

    public final static short PROTO_802_1D = 0;
    public final static byte DEC_CODE = (byte) 0xE1;

    // IEEE 802.1D-2004
    public final static int FRAME_SIZE = 35; // bytes
    public final static int DEC_FRAME_SIZE = 27; // bytes

    // TODO (galo) - look up those values
    public final static byte MESSAGE_TYPE_CONFIGURATION = 0;
    public final static byte MESSAGE_TYPE_TCNBPDU = 0;

    public final static byte FLAG_MASK_802_1D_TOPOLOGY_CHANGE = (byte)0x80;
    public final static byte FLAG_MASK_802_1D_TOPOLOGY_CHANGE_ACK = (byte)0x01;

    public final static byte FLAG_MASK_DEC_TOPOLOGY_CHANGE = (byte)0x80;
    public final static byte FLAG_MASK_DEC_1D_TOPOLOGY_CHANGE_ACK = (byte)0x01;

    private short protocolId;
    private byte versionId;
    private byte bpduMsgType;
    private byte flags;
    private long rootBridgeId;
    private int rootPathCost;
    private long senderBridgeId;
    private short portId;
    private short msgAge; // in 1/256 secs
    private short maxAge; // in 1/256 secs
    private short helloTime; // in 1/256 secs
    private short fwdDelay; // in 1/256 secs

    private byte topologyChangeMask;
    private byte topologyChangeAckMask;

    public short getProtocolId() {
        return protocolId;
    }

    public BPDU setProtocolId(short protocolId) {
        this.protocolId = protocolId;
        return this;
    }

    public byte getVersionId() {
        return versionId;
    }

    public BPDU setVersionId(byte versionId) {
        this.versionId = versionId;
        return this;
    }

    public byte getBpduMsgType() {
        return bpduMsgType;
    }

    public BPDU setBpduMsgType(byte bpduMsgType) {
        this.bpduMsgType = bpduMsgType;
        return this;
    }

    public byte getFlags() {
        return flags;
    }

    public BPDU setFlags(byte flags) {
        this.flags = flags;
        return this;
    }

    public long getRootBridgeId() {
        return rootBridgeId;
    }

    public BPDU setRootBridgeId(long rootBridgeId) {
        this.rootBridgeId = rootBridgeId;
        return this;
    }

    public int getRootPathCost() {
        return rootPathCost;
    }

    public BPDU setRootPathCost(int rootPathCost) {
        this.rootPathCost = rootPathCost;
        return this;
    }

    public long getSenderBridgeId() {
        return senderBridgeId;
    }

    public BPDU setSenderBridgeId(long senderBridgeId) {
        this.senderBridgeId = senderBridgeId;
        return this;
    }

    public short getPortId() {
        return portId;
    }

    public BPDU setPortId(short portId) {
        this.portId = portId;
        return this;
    }

    /**
     * @return the time, in 1/256 seconds.
     */
    public short getMsgAge() {
        return msgAge;
    }

    /**
     * @param msgAge the time, in 1/256 seconds.
     */
    public BPDU setMsgAge(short msgAge) {
        this.msgAge = msgAge;
        return this;
    }

    /**
     * @return the time, in 1/256 seconds.
     */
    public short getMaxAge() {
        return maxAge;
    }

    /**
     * @param maxAge the time, in 1/256 seconds.
     */
    public BPDU setMaxAge(short maxAge) {
        this.maxAge = maxAge;
        return this;
    }

    /**
     * @return the time, in 1/256 seconds.
     */
    public short getHelloTime() {
        return helloTime;
    }

    /**
     * @param helloTime the time, in 1/256 seconds.
     */
    public BPDU setHelloTime(short helloTime) {
        this.helloTime = helloTime;
        return this;
    }

    /**
     * @return the time, in 1/256 seconds.
     */
    public short getFwdDelay() {
        return fwdDelay;
    }

    /**
     * @param fwdDelay the time, in 1/256 seconds.
     */
    public BPDU setFwdDelay(short fwdDelay) {
        this.fwdDelay = fwdDelay;
        return this;
    }

    /**
     * Examines flags and determines whether the "topology change notice" field
     * is set to one.
     */
    public boolean hasTopologyChangeNotice() {
        return (flags & topologyChangeMask) != 0;
    }

    /**
     * Examines flags and determines whether the "topology change notice ack"
     * field is set to one.
     */
    public boolean hasTopologyChangeNoticeAck() {
        return (flags & topologyChangeAckMask) != 0;
    }

    @Override
    public String toString() {
        return "BPDU{" +
            "protocolId=" + protocolId + ", versionId=" + versionId +
            ", bpduMsgType=" + bpduMsgType + ", flags=" + flags +
            ", rootBridgeId=" + rootBridgeId + ", rootPathCost=" + rootPathCost +
            ", senderBridgeId=" + senderBridgeId + ", portId=" + portId +
            ", msgAge=" + msgAge + ", maxAge=" + maxAge +
            ", helloTime=" + helloTime + ", fwdDelay=" + fwdDelay + '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BPDU)) return false;
        if (!super.equals(o)) return false;

        BPDU bpdu = (BPDU) o;

        if (bpduMsgType != bpdu.bpduMsgType) return false;
        if (flags != bpdu.flags) return false;
        if (fwdDelay != bpdu.fwdDelay) return false;
        if (helloTime != bpdu.helloTime) return false;
        if (maxAge != bpdu.maxAge) return false;
        if (msgAge != bpdu.msgAge) return false;
        if (portId != bpdu.portId) return false;
        if (protocolId != bpdu.protocolId) return false;
        if (rootBridgeId != bpdu.rootBridgeId) return false;
        if (rootPathCost != bpdu.rootPathCost) return false;
        if (senderBridgeId != bpdu.senderBridgeId) return false;
        if (versionId != bpdu.versionId) return false;

        return true;
    }

    @Override
    public int hashCode() {
        int result = super.hashCode();
        result = 31 * result + (int) protocolId;
        result = 31 * result + (int) versionId;
        result = 31 * result + (int) bpduMsgType;
        result = 31 * result + (int) flags;
        result = 31 * result + (int) (rootBridgeId ^ (rootBridgeId >>> 32));
        result = 31 * result + rootPathCost;
        result = 31 * result + (int) (senderBridgeId ^ (senderBridgeId >>> 32));
        result = 31 * result + (int) portId;
        result = 31 * result + (int) msgAge;
        result = 31 * result + (int) maxAge;
        result = 31 * result + (int) helloTime;
        result = 31 * result + (int) fwdDelay;
        return result;
    }

    @Override
    public byte[] serialize() {
        ByteBuffer bb = ByteBuffer.allocate(BPDU.FRAME_SIZE);
        bb.putShort(protocolId);
        bb.put(versionId);
        bb.put(bpduMsgType);
        bb.put(flags);
        bb.putLong(rootBridgeId);
        bb.putInt(rootPathCost);
        bb.putLong(senderBridgeId);
        bb.putShort(portId);
        bb.putShort(msgAge);
        bb.putShort(maxAge);
        bb.putShort(helloTime);
        bb.putShort(fwdDelay);
        return bb.array();
    }

    @Override
    public IPacket deserialize(ByteBuffer bb) throws MalformedPacketException {
        try {
            byte type = bb.get();
            if (type == DEC_CODE)
                return deserializeDec(bb);
            if (type == 0 && bb.get() == 0)
                return deserialize8021d(bb);
        } catch (Exception e) {
            throw new MalformedPacketException("Malformed BPDU packet", e);
        }
        throw new MalformedPacketException("Unknown BPDU packet type");
    }

    private IPacket deserialize8021d(ByteBuffer bb) {
        protocolId = 0;
        topologyChangeMask = FLAG_MASK_802_1D_TOPOLOGY_CHANGE;
        topologyChangeAckMask = FLAG_MASK_802_1D_TOPOLOGY_CHANGE_ACK;
        versionId = bb.get();
        bpduMsgType = bb.get();
        flags = bb.get();
        rootBridgeId = bb.getLong();
        rootPathCost = bb.getInt();
        senderBridgeId = bb.getLong();
        portId = bb.getShort();
        msgAge = bb.getShort();
        maxAge = bb.getShort();
        helloTime = bb.getShort();
        fwdDelay = bb.getShort();
        return this;
    }

    private IPacket deserializeDec(ByteBuffer bb) {
        protocolId = DEC_CODE;
        topologyChangeMask = FLAG_MASK_DEC_TOPOLOGY_CHANGE;
        topologyChangeAckMask = FLAG_MASK_DEC_1D_TOPOLOGY_CHANGE_ACK;
        versionId = bb.get();
        bpduMsgType = bb.get();
        flags = bb.get();
        rootBridgeId = bb.getLong();
        rootPathCost = bb.getShort();
        senderBridgeId = bb.getLong();
        portId = bb.get();
        msgAge = bb.get();
        maxAge = bb.get();
        helloTime = bb.get();
        fwdDelay = bb.get();
        return this;
    }
}
