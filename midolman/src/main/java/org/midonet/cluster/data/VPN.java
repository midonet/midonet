/*
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.cluster.data;

import org.midonet.midolman.state.zkManagers.VpnZkManager;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class VPN extends Entity.Base<UUID, VPN.Data, VPN>{

    public enum Property {
    }

    public VPN() {
        this(null, new Data());
    }

    public VPN(UUID uuid, Data data) {
        super(uuid, data);
    }

    @Override
    protected VPN self() {
        return this;
    }

    public VpnZkManager.VpnType getVpnType() {
        return getData().vpnType;
    }

    public VPN setVpnType(VpnZkManager.VpnType vpnType) {
        getData().vpnType = vpnType;
        return this;
    }

    public String getRemoteIp() {
        return getData().remoteIp;
    }

    public VPN setRemoteIp(String remoteIp) {
        getData().remoteIp = remoteIp;
        return this;
    }

    public UUID getPublicPortId() {
        return getData().publicPortId;
    }

    public VPN setPublicPortId(UUID publicPortId) {
        getData().publicPortId = publicPortId;
        return this;
    }

    public UUID getPrivatePortId() {
        return getData().privatePortId;
    }

    public VPN setPrivatePortId(UUID privatePortId) {
        getData().privatePortId = privatePortId;
        return this;
    }

    public int getPort() {
        return getData().port;
    }

    public VPN setPort(int port) {
        getData().port = port;
        return this;
    }

    public VPN setProperty(Property property, String value) {
        getData().properties.put(property.name(), value);
        return this;
    }

    public VPN setProperties(Map<String, String> properties) {
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

        public UUID publicPortId;
        public UUID privatePortId;
        public String remoteIp;
        public VpnZkManager.VpnType vpnType;
        public int port;
        public Map<String, String> properties = new HashMap<String, String>();

    }
}
