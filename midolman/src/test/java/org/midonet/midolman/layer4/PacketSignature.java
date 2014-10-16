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

package org.midonet.midolman.layer4;

import org.midonet.packets.IPAddr;


public class PacketSignature {

    public byte protocol;
    public IPAddr nwSrc;
    public int tpSrc;
    public IPAddr nwDst;
    public int tpDst;

    public PacketSignature(byte protocol,
                           IPAddr nwSrc, int tpSrc, IPAddr nwDst, int tpDst) {
        super();
        this.protocol = protocol;
        this.nwSrc = nwSrc;
        this.tpSrc = tpSrc;
        this.nwDst = nwDst;
        this.tpDst = tpDst;
    }

    @Override
    public int hashCode() {
        return nwSrc.hashCode()*31 + tpSrc*17 + nwDst.hashCode()*23 + tpDst*29 +
               protocol*19;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other)
            return true;
        if (!(other instanceof PacketSignature))
            return false;
        PacketSignature p = (PacketSignature) other;
        return nwSrc.equals(p.nwSrc) && tpSrc == p.tpSrc &&
               nwDst.equals(p.nwDst) && tpDst == p.tpDst &&
               protocol == p.protocol;
    }

    @Override
    public String toString() {
        return "PacketSignature [nwSrc=" + nwSrc.toString() + ", tpSrc=" + tpSrc +
               ", nwDst=" + nwDst.toString() + ", tpDst=" + tpDst + ", protocol=" +
        protocol +"]";
    }
}
