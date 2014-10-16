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
package org.midonet.cluster.data;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.midonet.packets.IPv4Addr;

public class Route extends Entity.Base<UUID, Route.Data, Route> {

    public enum Property {
    }

    public Route() {
        this(null, new Data());
    }

    public Route(UUID id){
        this(id, new Data());
    }

    public Route(Data data){
        this(null, data);
    }

    public Route(UUID uuid, Data data) {
        super(uuid, data);
    }

    @Override
    protected Route self() {
        return this;
    }

    public String getSrcNetworkAddr() {
        return getData().srcNetworkAddr;
    }

    public Route setSrcNetworkAddr(String srcNetworkAddr) {
        getData().srcNetworkAddr = srcNetworkAddr;
        return this;
    }

    public String getDstNetworkAddr() {
        return getData().dstNetworkAddr;
    }

    public Route setDstNetworkAddr(String dstNetworkAddr) {
        getData().dstNetworkAddr = dstNetworkAddr;
        return this;
    }

    public int getSrcNetworkLength() {
        return getData().srcNetworkLength;
    }

    public Route setSrcNetworkLength(int srcNetworkLength) {
        getData().srcNetworkLength = srcNetworkLength;
        return this;
    }

    public int getDstNetworkLength() {
        return getData().dstNetworkLength;
    }

    public Route setDstNetworkLength(int dstNetworkLength) {
        getData().dstNetworkLength = dstNetworkLength;
        return this;
    }

    public org.midonet.midolman.layer3.Route.NextHop getNextHop() {
        return getData().nextHop;
    }

    public Route setNextHop(
            org.midonet.midolman.layer3.Route.NextHop nextHop) {
        getData().nextHop = nextHop;
        return this;
    }

    public UUID getNextHopPort() {
        return getData().nextHopPort;
    }

    public Route setNextHopPort(UUID nextHopPort) {
        getData().nextHopPort = nextHopPort;
        return this;
    }

    public String getNextHopGateway() {
        return getData().nextHopGateway;
    }

    public Route setNextHopGateway(String nextHopGateway) {
        getData().nextHopGateway = nextHopGateway;
        return this;
    }

    public int getWeight() {
        return getData().weight;
    }

    public Route setWeight(int weight) {
        getData().weight = weight;
        return this;
    }

    public String getAttributes() {
        return getData().attributes;
    }

    public Route setAttributes(String attributes) {
        getData().attributes = attributes;
        return this;
    }

    public UUID getRouterId() {
        return getData().routerId;
    }

    public Route setRouterId(UUID routerId) {
        getData().routerId = routerId;
        return this;
    }

    public Route setProperty(Property property, String value) {
        getData().properties.put(property.name(), value);
        return this;
    }

    public Route setProperties(Map<String, String> properties) {
        getData().properties = properties;
        return this;
    }

    public String getProperty(Property property) {
        return getData().properties.get(property.name());
    }

    public Map<String, String> getProperties() {
        return getData().properties;
    }

    @Override
    public boolean equals(Object other) {
        if (other == null)
            return false;
        if (other == this)
            return true;
        if (!(other instanceof Route))
            return false;
        Route rt = (Route)other;
        return getData().equals(rt.getData());
    }

    @Override
    public int hashCode() {
        return getData().hashCode();
    }

    public static class Data {

        public String srcNetworkAddr;
        public int srcNetworkLength;
        public String dstNetworkAddr;
        public int dstNetworkLength;
        public org.midonet.midolman.layer3.Route.NextHop nextHop;
        public UUID nextHopPort;
        public String nextHopGateway;
        public int weight;
        public String attributes;
        public UUID routerId;
        public Map<String, String> properties = new HashMap<String, String>();

        public Data() {
            srcNetworkAddr = "0.0.0.0";
            srcNetworkLength = 0;
            dstNetworkAddr = "0.0.0.0";
            dstNetworkLength = 0;
            nextHop = org.midonet.midolman.layer3.Route.NextHop.REJECT;
            nextHopPort = UUID.fromString("deadcafe-dead-c0de-dead-beefdeadbeef");
            nextHopGateway = IPv4Addr.intToString(
                org.midonet.midolman.layer3.Route.NO_GATEWAY);
            weight = 0;
            attributes = "";
            routerId = UUID.fromString("deadcafe-dead-c0de-dead-beefdeadbeef");
        }
        @Override
        public boolean equals(Object other) {
            if (other == null)
                return false;
            if (other == this)
                return true;
            if (!(other instanceof Data))
                return false;
            Data rt = (Data) other;
            if (null == nextHop || null == rt.nextHop) {
                if (nextHop != rt.nextHop)
                    return false;
            } else if (!nextHop.equals(rt.nextHop))
                return false;
            if (null == nextHopPort || null == rt.nextHopPort) {
                if (nextHopPort != rt.nextHopPort)
                    return false;
            } else if (!nextHopPort.equals(rt.nextHopPort))
                return false;
            if (null == attributes || null == rt.attributes) {
                if (attributes != rt.attributes)
                    return false;
            } else if (!attributes.equals(rt.attributes))
                return false;
            if (null == routerId || null == rt.routerId) {
                if (routerId != rt.routerId)
                    return false;
            } else if (!routerId.equals(rt.routerId))
                return false;

            if (null == nextHopGateway || null == rt.nextHopGateway) {
                if (nextHopGateway != rt.nextHopGateway)
                    return false;
            } else if (!nextHopGateway.equals(rt.nextHopGateway))
                return false;

            return dstNetworkAddr.equals(rt.dstNetworkAddr)
                    && dstNetworkLength == rt.dstNetworkLength
                    && srcNetworkAddr.equals(rt.srcNetworkAddr)
                    && srcNetworkLength == rt.srcNetworkLength
                    && weight == rt.weight;
        }

        @Override
        public int hashCode() {
            int hash = 1;
            hash = 13 * hash + srcNetworkAddr.hashCode();
            hash = 17 * hash + srcNetworkLength;
            hash = 31 * hash + dstNetworkAddr.hashCode();
            hash = 23 * hash + dstNetworkLength;
            hash = 11 * hash + weight;

            if (null != routerId)
                hash = 47 * hash + routerId.hashCode();
            if (null != nextHop)
                hash = 29 * hash + nextHop.hashCode();
            if (null != nextHopPort)
                hash = 43 * hash + nextHopPort.hashCode();
            if (null != attributes)
                hash = 5 * hash + attributes.hashCode();
            if (null != nextHopGateway) {
                hash = 37 * hash + nextHopGateway.hashCode();
            }
            return hash;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(srcNetworkAddr).append(",");
            sb.append(srcNetworkLength).append(",");
            sb.append(dstNetworkAddr).append(",");
            sb.append(dstNetworkLength).append(",");
            if (null != nextHop)
                sb.append(nextHop.toString());
            sb.append(",");
            if (null != nextHopPort)
                sb.append(nextHopPort.toString());
            sb.append(",");
            sb.append(nextHopGateway).append(",");
            sb.append(weight).append(",");
            if (null != attributes)
                sb.append(attributes);
            if (null != routerId)
                sb.append(routerId);
            return sb.toString();
        }

    }
}
