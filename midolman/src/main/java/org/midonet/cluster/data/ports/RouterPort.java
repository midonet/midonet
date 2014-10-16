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
package org.midonet.cluster.data.ports;

import java.util.Random;
import java.util.Set;
import java.util.UUID;

import org.midonet.cluster.data.BGP;
import org.midonet.cluster.data.Port;
import org.midonet.packets.MAC;
import org.midonet.packets.IPv4Addr;

import javax.annotation.Nonnull;


/**
 * Basic abstraction for a Router Port.
 */
public class RouterPort
        extends Port<RouterPort.Data, RouterPort>
{
    public static Random rand = new Random(System.currentTimeMillis());

    public RouterPort(UUID routerId, UUID uuid, Data portData){
        super(uuid, portData);
        if (getData() != null && routerId != null)
            setDeviceId(routerId);

        if (getData() != null && portData.hwAddr == null) {
            setHwAddr(MAC.random());
        }
    }

    public RouterPort(UUID uuid, Data data) {
        this(null, uuid, data);
    }

    public RouterPort(@Nonnull Data data) {
        this(null, null, data);
    }

    public RouterPort() {
        this(null, null, new Data());
    }

    public String getNwAddr() {
        return IPv4Addr.intToString(getData().nwAddr);
    }

    public RouterPort setNwAddr(String nwAddr) {
        getData().nwAddr = IPv4Addr.stringToInt(nwAddr);
        return this;
    }

    public int getNwLength() {
        return getData().nwLength;
    }

    public RouterPort setNwLength(int nwLength) {
        getData().nwLength = nwLength;
        return this;
    }

    public String getPortAddr() {
        return IPv4Addr.intToString(getData().portAddr);
    }

    public RouterPort setPortAddr(String portAddr) {
        getData().portAddr = IPv4Addr.stringToInt(portAddr);
        return this;
    }

    public MAC getHwAddr() {
        return getData().hwAddr;
    }

    public RouterPort setHwAddr(MAC hwAddr) {
        getData().hwAddr = hwAddr;
        return this;
    }

    @Override
    protected RouterPort self() {
        return this;
    }

    public Set<BGP> getBgps() {
        return getData().bgps;
    }

    public static class Data extends Port.Data {
        public int nwAddr;
        public int nwLength;
        public int portAddr;
        public MAC hwAddr;
        public transient Set<BGP> bgps;

        @Override
        public boolean equals(Object o) {

            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            if (!super.equals(o)) return false;

            Data data = (Data) o;

            if (nwAddr != data.nwAddr) return false;
            if (nwLength != data.nwLength) return false;
            if (portAddr != data.portAddr) return false;
            if (hwAddr != null ? !hwAddr.equals(
                data.hwAddr) : data.hwAddr != null)
                return false;
            if (bgps != null ? !bgps.equals(data.bgps) : data.bgps != null)
                return false;

            return true;
        }

        @Override
        public int hashCode() {
            int result = super.hashCode();
            result = 31 * result + nwAddr;
            result = 31 * result + nwLength;
            result = 31 * result + portAddr;
            result = 31 * result + (hwAddr != null ? hwAddr.hashCode() : 0);
            result = 31 * result + (bgps != null ? bgps.hashCode() : 0);
            return result;
        }

        @Override
        public String toString() {
            return "Data{" +
                    "nwAddr=" + IPv4Addr.intToString(nwAddr) +
                    ", nwLength=" + nwLength +
                    ", portAddr=" + IPv4Addr.intToString(portAddr) +
                    ", hwAddr=" + hwAddr +
                    ", bgps=" + bgps +
                    '}';
        }
    }
}

