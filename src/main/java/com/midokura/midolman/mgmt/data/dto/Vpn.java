/*
 * @(#)Vpn        1.6 11/10/25
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.dto;

import java.util.UUID;

import javax.xml.bind.annotation.XmlRootElement;

import com.midokura.midolman.state.VpnZkManager.VpnConfig;

/**
 * Class representing VPN.
 * 
 * @version 1.6 25 Oct 2011
 * @author Yoshi Tamura
 */
@XmlRootElement
public class Vpn {

    private UUID id = null;
    private int port;
    private UUID portId = null;

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
     * Get port ID.
     * 
     * @return Port ID.
     */
    public UUID getPortId() {
        return portId;
    }

    /**
     * Set port ID.
     * 
     * @param portId
     *            Port ID of the VPN.
     */
    public void setPortId(UUID portId) {
        this.portId = portId;
    }

    public VpnConfig toConfig() {
        return new VpnConfig(this.getPortId(), this.getPort());

    }

    public static Vpn createVpn(UUID id, VpnConfig config) {
        Vpn b = new Vpn();
        b.setPort(config.port);
        b.setPortId(config.portId);
        b.setId(id);
        return b;
    }
}
