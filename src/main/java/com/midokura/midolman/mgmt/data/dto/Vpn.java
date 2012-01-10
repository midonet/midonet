/*
 * @(#)Vpn        1.6 11/10/25
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.dto;

import java.net.URI;
import java.util.UUID;

import javax.xml.bind.annotation.XmlRootElement;

import com.midokura.midolman.mgmt.rest_api.core.UriManager;
import com.midokura.midolman.state.VpnZkManager;
import com.midokura.midolman.state.VpnZkManager.VpnConfig;

/**
 * Class representing VPN.
 *
 * @version 1.6 25 Oct 2011
 * @author Yoshi Tamura
 */
@XmlRootElement
public class Vpn extends UriResource {

    private UUID id = null;
    private int port;
    private UUID publicPortId = null;
    private UUID privatePortId = null;
    private String remoteIp;
    private VpnZkManager.VpnType vpnType;

    /**
     * Constructor
     */
    public Vpn() {
    }

    /**
     * Constructor
     *
     * @param id
     *            ID of the VPN
     * @param config
     *            VpnConfig object
     */
    public Vpn(UUID id, VpnConfig config) {
        this.port = config.port;
        this.privatePortId = config.privatePortId;
        this.publicPortId = config.publicPortId;
        this.remoteIp = config.remoteIp;
        this.vpnType = config.vpnType;
        this.id = id;
    }

    /**
     * Get VPN ID.
     *
     * @return VPN ID.
     */
    public UUID getId() {
        return id;
    }

    /**
     * Set VPN ID.
     *
     * @param id
     *            ID of the VPN.
     */
    public void setId(UUID id) {
        this.id = id;
    }

    /**
     * Get VPN port.
     *
     * @return VPN port.
     */
    public int getPort() {
        return port;
    }

    /**
     * Set VPN port.
     *
     * @param port
     *            port of the VPN.
     */
    public void setPort(int port) {
        this.port = port;
    }

    /**
     * @return the self URI
     */
    @Override
    public URI getUri() {
        return UriManager.getVpn(getBaseUri(), id);
    }

    /**
     * Get public port ID.
     *
     * @return Public port ID.
     */
    public UUID getPublicPortId() {
        return publicPortId;
    }

    /**
     * Set public port ID.
     *
     * @param publicPortId
     *            Public port ID of the VPN.
     */
    public void setPublicPortId(UUID publicPortId) {
        this.publicPortId = publicPortId;
    }

    /**
     * Get private port ID.
     *
     * @return Private port ID.
     */
    public UUID getPrivatePortId() {
        return privatePortId;
    }

    /**
     * Set private port ID.
     *
     * @param privatePortId
     *            Private port ID of the VPN.
     */
    public void setPrivatePortId(UUID privatePortId) {
        this.privatePortId = privatePortId;
    }

    /**
     * Get remote IP.
     *
     * @return IntIPv4.
     */
    public String getRemoteIp() {
        return remoteIp;
    }

    /**
     * Set remote IP.
     *
     * @param remoteIp
     *            Remote IP.
     */
    public void setRemoteIp(String remoteIp) {
        this.remoteIp = remoteIp;
    }

    /**
     * Get VPN type.
     *
     * @return VPN type.
     */
    public VpnZkManager.VpnType getType() {
        return vpnType;
    }

    /**
     * Set VPN type.
     *
     * @param id
     *            ID of the type.
     */
    public void setVpnType(VpnZkManager.VpnType vpnType) {
        this.vpnType = vpnType;
    }

    public VpnConfig toConfig() {
        return new VpnConfig(this.getPublicPortId(), this.getPrivatePortId(),
                this.getRemoteIp(), this.vpnType, this.getPort());

    }

    /*
     * (non-Javadoc)
     *
     * @see java.lang.Object#toString()
     */
    @Override
    public String toString() {
        return "id=" + id + ", vpnType=" + vpnType + ", port=" + port;
    }

}
