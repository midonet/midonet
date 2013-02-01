/*
 * Copyright 2011 Midokura Europe SARL
 */

package org.midonet.client.dto;

import javax.xml.bind.annotation.XmlRootElement;
import java.net.URI;
import java.util.UUID;

@XmlRootElement
public class DtoVpn {

    public static enum VpnType {
        OPENVPN_SERVER, OPENVPN_CLIENT, OPENVPN_TCP_SERVER, OPENVPN_TCP_CLIENT
    }

    private URI uri;
    private UUID id;
    private int port;
    private UUID publicPortId;
    private UUID privatePortId;
    private String remoteIp;
    private VpnType vpnType;

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

    public VpnType getVpnType() {
        return vpnType;
    }

    public void setVpnType(VpnType vpnType) {
        this.vpnType = vpnType;
    }
}
