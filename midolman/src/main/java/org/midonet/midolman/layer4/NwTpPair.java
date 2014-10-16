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

public class NwTpPair {

    public final IPAddr nwAddr;
    public final int tpPort;
    public final String unrefKey;

    public NwTpPair(IPAddr nwAddr, int tpPort) {
        this.nwAddr = nwAddr;
        this.tpPort = tpPort;
        this.unrefKey = null;
    }

    public NwTpPair(IPAddr nwAddr, int tpPort, String unrefKey) {
        this.nwAddr = nwAddr;
        this.tpPort = tpPort;
        this.unrefKey = unrefKey;
    }

    @Override
    public int hashCode() {
        return nwAddr.hashCode() * 31 + tpPort * 17;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other)
            return true;
        if (!(other instanceof NwTpPair))
            return false;
        NwTpPair p = (NwTpPair) other;
        if (nwAddr != null && p.nwAddr == null)
            return false;
        return nwAddr.equals(p.nwAddr) && tpPort == p.tpPort;
    }

    @Override
    public String toString() {
        return "NwTpPair [nwAddr=" + nwAddr.toString() + ", tpPort=" + tpPort + "]";
    }
}
