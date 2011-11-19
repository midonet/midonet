/*
 * @(#)MaterializedRouterPort        1.6 18/09/05
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.dto;

import java.net.URI;
import java.util.HashSet;
import java.util.UUID;

import javax.xml.bind.annotation.XmlRootElement;

import com.midokura.midolman.layer3.Route;
import com.midokura.midolman.mgmt.data.dto.config.PortMgmtConfig;
import com.midokura.midolman.mgmt.rest_api.core.UriManager;
import com.midokura.midolman.state.BGP;
import com.midokura.midolman.state.PortConfig;
import com.midokura.midolman.state.PortDirectory;
import com.midokura.midolman.util.Net;

/**
 * Data transfer class for materialized router port.
 * 
 * @version 1.6 18 Sept 2011
 * @author Ryu Ishimoto
 */
@XmlRootElement
public class MaterializedRouterPort extends RouterPort {

    private String localNetworkAddress = null;
    private int localNetworkLength;

    /**
     * @return the localNetworkAddress
     */
    public String getLocalNetworkAddress() {
        return localNetworkAddress;
    }

    /**
     * @param localNetworkAddress
     *            the localNetworkAddress to set
     */
    public void setLocalNetworkAddress(String localNetworkAddress) {
        this.localNetworkAddress = localNetworkAddress;
    }

    /**
     * @return the localNetworkLength
     */
    public int getLocalNetworkLength() {
        return localNetworkLength;
    }

    /**
     * @param localNetworkLength
     *            the localNetworkLength to set
     */
    public void setLocalNetworkLength(int localNetworkLength) {
        this.localNetworkLength = localNetworkLength;
    }

    /**
     * @return the bgps URI
     */
    public URI getBgps() {
        return UriManager.getPortBgps(getBaseUri(), this);
    }

    /**
     * @return the vpns URI
     */
    public URI getVpns() {
        return UriManager.getPortVpns(getBaseUri(), this);
    }

    /**
     * Convert this object to PortConfig object.
     * 
     * @return PortConfig object.
     */
    @Override
    public PortConfig toConfig() {
        return new PortDirectory.MaterializedRouterPortConfig(
                this.getDeviceId(), Net.convertStringAddressToInt(this
                        .getNetworkAddress()), this.getNetworkLength(),
                Net.convertStringAddressToInt(this.getPortAddress()),
                new HashSet<Route>(), Net.convertStringAddressToInt(this
                        .getLocalNetworkAddress()),
                this.getLocalNetworkLength(), new HashSet<BGP>());
    }

    /**
     * Convert PortMgmtConfig and MaterializedRouterPortConfig objects to Port
     * object.
     * 
     * @param id
     *            ID of the object.
     * @param mgmtConfig
     *            PortMgmtConfig object.
     * @param config
     *            MaterializedRouterPortConfig object.
     * @return Port object.
     */
    public static Port createPort(UUID id, PortMgmtConfig mgmtConfig,
            PortDirectory.MaterializedRouterPortConfig config) {
        MaterializedRouterPort port = new MaterializedRouterPort();
        port.setDeviceId(config.device_id);
        port.setNetworkAddress(Net.convertIntAddressToString(config.nwAddr));
        port.setNetworkLength(config.nwLength);
        port.setPortAddress(Net.convertIntAddressToString(config.portAddr));
        port.setLocalNetworkAddress(Net
                .convertIntAddressToString(config.localNwAddr));
        port.setLocalNetworkLength(config.localNwLength);
        port.setVifId(mgmtConfig.vifId);
        port.setId(id);
        return port;
    }
}