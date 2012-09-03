/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midonet.cluster.data;

import java.net.InetAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class BGP extends Entity.Base<UUID, BGP.Data, BGP>{

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

    public InetAddress getPeerAddr() {
        return getData().peerAddr;
    }

    public BGP setPeerAddr(InetAddress peerAddr) {
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
        // TODO: Why is this an InetAddress instead of an IntIPv4?
        public InetAddress peerAddr;
        public int peerAS;
        public UUID portId;
        public Map<String, String> properties = new HashMap<String, String>();

    }
}
