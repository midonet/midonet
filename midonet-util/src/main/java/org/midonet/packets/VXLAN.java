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

package org.midonet.packets;

import java.nio.ByteBuffer;

public class VXLAN extends BasePacket {
    private static final int HEADER_LEN = 8;
    private int vni;

    public int getVni() {
        return vni;
    }

    public void setVni(int vni) {
        this.vni = vni;
    }

    @Override
    public int length() {
        return HEADER_LEN + getPayloadLength();
    }

    @Override
    public byte[] serialize() {
        byte[] payloadData = null;
        if (payload != null) {
            payload.setParent(this);
            payloadData = payload.serialize();
        }
        int length = 8 + ((payloadData == null) ? 0 : payloadData.length);
        byte[] bytes = new byte[length];
        ByteBuffer bb = ByteBuffer.wrap(bytes);
        bb.putInt(0x1 << 27);
        bb.putInt(vni << 8);
        if (payloadData != null)
            bb.put(payloadData);
        return bytes;
    }

    @Override
    public IPacket deserialize(ByteBuffer bb) throws MalformedPacketException {
        if (bb.remaining() < HEADER_LEN) {
            throw new MalformedPacketException("Invalid VXLAN length: "
                    + bb.remaining());
        }

        bb.getInt(); // Ignore the first 4 bytes
        vni = bb.getInt() >>> 8;
        int start = bb.position();
        int end = bb.limit();
        try {
            payload = (new Ethernet()).deserialize(bb);
        } catch (Exception e) {
            payload = (new Data()).deserialize(bb);
        }
        payload.setParent(this);
        bb.limit(end);
        bb.position(start);
        return this;
    }
}
