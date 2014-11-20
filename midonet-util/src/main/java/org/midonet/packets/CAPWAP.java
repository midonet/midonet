/*
 * Copyright 2014 Midokura SARL
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

package org.midonet.packets;

import java.nio.ByteBuffer;
import java.util.Arrays;

/*
 *  RFC 5415
 *  Sec. 4.3 CAPWAP Header:
 *
 *   0                   1                   2                   3
 *   0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 *  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *  |Version| Type  |  HLEN   |   RID   | WBID    |T|F|L|W|M|K|Flags|
 *  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *  |          Fragment ID          |     Frag Offset         |Rsvd |
 *  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *  |MAC len (opt)  | (optional) Radio MAC Address 4-byte aligned   |
 *  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *  |WInfo len (opt)| (opt.) Wireless Specific Info 4-byte aligned  |
 *  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *  |                        Payload ....                           |
 *  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 */
public class CAPWAP extends BasePacket {
    public static final short OVS_SOURCE_UDP_PORT = (short) 58881;
    public static final short OVS_TARGET_UDP_PORT = (short) 58882;

    byte version, type, hlen, radioId, wbId;
    boolean isNativeFrame;   // T
    boolean isFragment;      // F
    boolean isLastFragment;  // L
    boolean hasWirelessInfo; // W
    boolean hasRadioMac;     // M
    boolean isKeepAlive;     // K
    short fragmentId, fragmentOffset;
    byte rMacLen;
    byte[] rMacBytes;
    byte wInfoLen;
    byte[] wInfoBytes;

    @Override
    public byte[] serialize() {
        int length = 8;
        if (hasRadioMac)
            length += 4 * (rMacLen /4 + 1);
        if (hasWirelessInfo)
            length += 4 * (wInfoLen /4 + 1);
        byte[] payloadData = null;
        if (payload != null) {
            payload.setParent(this);
            payloadData = payload.serialize();
            length += payloadData.length;
        }
        byte[] bytes = new byte[length];
        ByteBuffer bb = ByteBuffer.wrap(bytes);

        int tmp = 0;
        tmp |= ((int)version) << 28;
        tmp |= ((int)type) << 24;
        tmp |= ((int)hlen) << 19;
        tmp |= ((int)radioId) << 14;
        tmp |= ((int)wbId) << 9;
        tmp |= (isNativeFrame   ? 1 : 0) << 8;
        tmp |= (isFragment      ? 1 : 0) << 7;
        tmp |= (isLastFragment  ? 1 : 0) << 6;
        tmp |= (hasWirelessInfo ? 1 : 0) << 5;
        tmp |= (hasRadioMac     ? 1 : 0) << 4;
        tmp |= (isKeepAlive     ? 1 : 0) << 3;
        bb.putInt(tmp);
        bb.putInt((fragmentId << 16) & (fragmentOffset << 3));
        if (hasRadioMac) {
            bb.put(rMacLen);
            bb.put(rMacBytes);
        }
        if (hasWirelessInfo) {
            bb.put(wInfoLen);
            bb.put(wInfoBytes);
        }
        return bytes;
    }

    @Override
    public int hashCode() {
        final int prime = 13121;
        int result = super.hashCode();
        result = prime * result + ((version << 4) | (type));
        result = prime * result + hlen;
        result = prime * result + radioId;
        result = prime * result + wbId;
        result = prime * result +
                ((isNativeFrame   ? 1 : 0) << 6) |
                ((isFragment      ? 1 : 0) << 5) |
                ((isLastFragment  ? 1 : 0) << 4) |
                ((hasWirelessInfo ? 1 : 0) << 3) |
                ((hasRadioMac     ? 1 : 0) << 2) |
                ((isKeepAlive     ? 1 : 0) << 1);
        result = prime * result + fragmentId;
        result = prime * result + fragmentOffset;
        if (hasRadioMac)
            result = prime * result + Arrays.hashCode(rMacBytes);
        if (hasWirelessInfo)
            result = prime * result + Arrays.hashCode(wInfoBytes);
        return result;
    }

    @Override
    public IPacket deserialize(ByteBuffer bb) throws MalformedPacketException {
        if (bb.remaining() < 8) {
            throw new MalformedPacketException("Invalid CAPWAP length: "
                                               + bb.remaining());
        }

        int tmp = bb.getInt();
        this.version = (byte)((tmp >>> 28) & 0x0f);
        this.type = (byte)((tmp >>> 24) & 0x0f);
        this.hlen = (byte)((tmp >>> 19) & 0x1f);
        this.radioId = (byte)((tmp >>> 14) & 0x1f);
        this.wbId = (byte)((tmp >>> 9) & 0x1f);

        byte flags = (byte)((tmp >>> 3) & 0x3f);
        this.isKeepAlive =     (flags & 1 << 0) != 0;
        this.hasRadioMac =     (flags & 1 << 1) != 0;
        this.hasWirelessInfo = (flags & 1 << 2) != 0;
        this.isLastFragment =  (flags & 1 << 3) != 0;
        this.isFragment =      (flags & 1 << 4) != 0;
        this.isNativeFrame =   (flags & 1 << 5) != 0;

        this.fragmentId = bb.getShort();
        this.fragmentOffset = (short)(bb.getShort() >>> 3);

        if (hasRadioMac) {
            this.rMacLen = bb.get();
            // 4-byte padding, 1st byte is the length
            int count = (4 * (rMacLen /4 + 1)) - 1;
            this.rMacBytes = new byte[count];
            bb.get(rMacBytes);
        }
        if (hasWirelessInfo) {
            this.wInfoLen = bb.get();
            // 4-byte padding, 1st byte is the length
            int count = (4 * (wInfoLen /4 + 1)) - 1;
            this.wInfoBytes = new byte[count];
            bb.get(wInfoBytes);
        }

        IPacket payload = new Ethernet();
        this.payload = payload.deserialize(bb.slice());
        this.payload.setParent(this);
        return this;
    }

    private String toHex(short value) { return Integer.toHexString(value); }
    private String toHex(int value) { return Integer.toHexString(value); }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("CAPWAP [version=0x").append(toHex(version & 0x0f));
        sb.append(", type=0x").append(toHex(type & 0x0f));
        sb.append(", hlen=0x").append(toHex(hlen & 0x1f));
        sb.append(", radioId=0x").append(toHex(radioId & 0x1f));
        sb.append(", wbId=0x").append(toHex(wbId & 0x1f));
        sb.append(", fragId=0x").append(toHex(fragmentId));
        sb.append(", fragOffset=0x").append(toHex(fragmentOffset));
        sb.append(", T=").append(isNativeFrame ? "1" : "0");
        sb.append(", F=").append(isFragment ? "1" : "0");
        sb.append(", L=").append(isLastFragment ? "1" : "0");
        sb.append(", W=").append(hasWirelessInfo ? "1" : "0");
        sb.append(", M=").append(hasRadioMac ? "1" : "0");
        sb.append(", K=").append(isKeepAlive ? "1" : "0");
        if (hasRadioMac)
            sb.append(", radioMac=").append(Arrays.toString(rMacBytes));
        if (hasWirelessInfo)
            sb.append(", wInfo=").append(Arrays.toString(wInfoBytes));

        sb.append(", payload=");
        sb.append(payload == null ? "null" : payload.toString());
        sb.append("]");
        return sb.toString();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (!super.equals(obj))
            return false;
        if (!(obj instanceof CAPWAP))
            return false;
        CAPWAP other = (CAPWAP) obj;

        if (version != other.version)
            return false;
        if (type != other.type)
            return false;
        if (hlen != other.hlen)
            return false;
        if (radioId != other.radioId)
            return false;
        if (wbId != other.wbId)
            return false;
        if (fragmentId != other.fragmentId)
            return false;
        if (fragmentOffset != other.fragmentOffset)
            return false;
        if (rMacLen != other.rMacLen)
            return false;
        if (wInfoLen != other.wInfoLen)
            return false;
        if (isNativeFrame != other.isNativeFrame)
            return false;
        if (isFragment != other.isFragment)
            return false;
        if (isLastFragment != other.isLastFragment)
            return false;
        if (hasWirelessInfo != other.hasWirelessInfo)
            return false;
        if (hasRadioMac != other.hasRadioMac)
            return false;
        if (isKeepAlive != other.isKeepAlive)
            return false;

        if (hasRadioMac && !Arrays.equals(rMacBytes, other.rMacBytes))
            return false;
        if (hasWirelessInfo && !Arrays.equals(wInfoBytes, other.wInfoBytes))
            return false;

        return true;
    }
}
