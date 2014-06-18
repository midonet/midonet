/*
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.cluster.data;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.midonet.packets.IPv4Addr;


/* TODO(abel): This BGP class should be split into two classes:
 * - one that deals with the actual BGP connection:
 *   - local AS
 *   - BGP router Id
 * - one that deals with the BGP peers for one BGP connection:
 *   - peer IP address
 *   - peer AS number
 *   - (maybe) port Id
 */
public class BGP extends Entity.Base<UUID, BGP.Data, BGP> {

    public enum Property {
    }

    public BGP() {
        this(null, new Data());
    }

    public BGP(UUID uuid, Data data) {
        super(uuid, data);
    }

    @Override
    protected BGP self() {
        return this;
    }

    public int getLocalAS() {
        return getData().localAS;
    }

    public BGP setLocalAS(int localAS) {
        getData().localAS = localAS;
        return this;
    }

    public int getPeerAS() {
        return getData().peerAS;
    }

    public BGP setPeerAS(int peerAS) {
        getData().peerAS = peerAS;
        return this;
    }

    public IPv4Addr getPeerAddr() {
        return getData().peerAddr;
    }

    public BGP setPeerAddr(IPv4Addr peerAddr) {
        getData().peerAddr = peerAddr;
        return this;
    }

    public UUID getPortId() {
        return getData().portId;
    }

    public BGP setPortId(UUID portId) {
        getData().portId = portId;
        return this;
    }

    public BGP setProperty(Property property, String value) {
        getData().properties.put(property.name(), value);
        return this;
    }

    public BGP setProperties(Map<String, String> properties) {
        getData().properties = properties;
        return this;
    }

    public String getProperty(Property property) {
        return getData().properties.get(property.name());
    }

    public Map<String, String> getProperties() {
        return getData().properties;
    }

    public static class Data {
       /*
        * The bgp is a list of BGP information dictionaries enabled on this
        * port. The keys for the dictionary are:
        *
        * local_port: local TCP port number for BGP, as a positive integer.
        * local_as: local AS number that belongs to, as a positive integer.
        * peer_addr: IPv4 address of the peer, as a human-readable string.
        * peer_port: TCP port number at the peer, as a positive integer, as a
        * string. tcp_md5sig_key: TCP MD5 signature to authenticate a session.
        * ad_routes: A list of routes to be advertised. Each item is a list
        * [address, length] that represents a network prefix, where address is
        * an IPv4 address as a human-readable string, and length is a positive
        * integer.
        */
        public int localAS;
        public IPv4Addr peerAddr;
        public int peerAS;
        public UUID portId;
        public Map<String, String> properties = new HashMap<>();

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            Data data = (Data) o;

            if (localAS != data.localAS) return false;
            if (peerAS != data.peerAS) return false;
            if (peerAddr != null ? !peerAddr.equals(data.peerAddr) : data.peerAddr != null)
                return false;
            if (portId != null ? !portId.equals(data.portId) : data.portId != null)
                return false;
            if (properties != null ? !properties.equals(data.properties) : data.properties != null)
                return false;

            return true;
        }

        @Override
        public int hashCode() {
            int result = localAS;
            result = 31 * result + (peerAddr != null ? peerAddr.hashCode() : 0);
            result = 31 * result + peerAS;
            result = 31 * result + (portId != null ? portId.hashCode() : 0);
            result = 31 * result + (properties != null ? properties.hashCode() : 0);
            return result;
        }

        @Override
        public String toString() {
            return "Data{" +
                    "localAS=" + localAS +
                    ", peerAddr=" + peerAddr +
                    ", peerAS=" + peerAS +
                    ", portId=" + portId +
                    ", properties=" + properties +
                    '}';
        }
    }
}
