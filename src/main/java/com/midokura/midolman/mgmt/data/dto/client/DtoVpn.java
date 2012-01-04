/*
 * Copyright 2011 Midokura Europe SARL
 */

package com.midokura.midolman.mgmt.data.dto.client;

import java.net.URI;
import java.util.UUID;

import javax.xml.bind.annotation.XmlRootElement;

import com.midokura.midolman.packets.IntIPv4;
import com.midokura.midolman.state.VpnZkManager;

@XmlRootElement
public class DtoVpn {
    private URI uri;
    private UUID id;
    private int port;
    private UUID publicPortId;
    private UUID privatePortId;
    private String remoteIp;
    private VpnZkManager.VpnType vpnType;

    public URI getUri() {
        return uri;
    }

    public void setUri(URI uri) {
        this.uri = uri;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public UUID getPublicPortId() {
        return publicPortId;
    }

    public void setPublicPortId(UUID publicPortId) {
        this.publicPortId = publicPortId;
    }

    public UUID getPrivatePortId() {
        return privatePortId;
    }

    public void setPrivatePortId(UUID privatePortId) {
        this.privatePortId = privatePortId;
    }

    public String getRemoteIp() {
        return remoteIp;
    }

    public void setRemoteIp(String remoteIp) {
        this.remoteIp = remoteIp;
    }

    public VpnZkManager.VpnType getVpnType() {
        return vpnType;
    }

    public void setVpnType(VpnZkManager.VpnType vpnType) {
        this.vpnType = vpnType;
    }
}
