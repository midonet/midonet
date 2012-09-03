/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.vpn;

import com.midokura.midolman.mgmt.UriResource;
import com.midokura.midolman.mgmt.ResourceUriBuilder;
import com.midokura.midolman.state.zkManagers.VpnZkManager;
import com.midokura.midonet.cluster.data.VPN;

import javax.xml.bind.annotation.XmlRootElement;
import java.net.URI;
import java.util.UUID;

/**
 * Class representing VPN.
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
     * @param data
     *            VPN data object
     */
    public Vpn(VPN data) {
        this.port = data.getPort();
        this.privatePortId = data.getPrivatePortId();
        this.publicPortId = data.getPublicPortId();
        this.remoteIp = data.getRemoteIp();
        this.vpnType = data.getVpnType();
        this.id = data.getId();
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
        if (getBaseUri() != null && id != null) {
            return ResourceUriBuilder.getVpn(getBaseUri(), id);
        } else {
            return null;
        }
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
     * @return the pubilc port URI
     */
    public URI getPublicPort() {
        if (getBaseUri() != null && publicPortId != null) {
            return ResourceUriBuilder.getPort(getBaseUri(), publicPortId);
        } else {
            return null;
        }
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
     * @return the private port URI
     */
    public URI getPrivatePort() {
        if (getBaseUri() != null && privatePortId != null) {
            return ResourceUriBuilder.getPort(getBaseUri(), privatePortId);
        } else {
            return null;
        }
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
    public VpnZkManager.VpnType getVpnType() {
        return vpnType;
    }

    /**
     * Set VPN type.
     *
     * @param vpnType
     *            ID of the type.
     */
    public void setVpnType(VpnZkManager.VpnType vpnType) {
        this.vpnType = vpnType;
    }

    public VPN toData() {
        return new VPN()
                .setId(this.id)
                .setVpnType(this.vpnType)
                .setPort(this.port)
                .setPrivatePortId(this.privatePortId)
                .setPublicPortId(this.publicPortId)
                .setRemoteIp(this.remoteIp);
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
