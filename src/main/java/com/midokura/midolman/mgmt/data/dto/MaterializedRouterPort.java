/*
 * @(#)MaterializedRouterPort        1.6 18/09/05
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.dto;

import java.util.HashSet;
import java.util.UUID;

import com.midokura.midolman.layer3.Route;
import com.midokura.midolman.mgmt.data.state.PortZkManagerProxy.PortMgmtConfig;
import com.midokura.midolman.state.BGP;
import com.midokura.midolman.state.PortDirectory.MaterializedRouterPortConfig;
import com.midokura.midolman.state.PortDirectory.PortConfig;
import com.midokura.midolman.util.Net;

/**
 * Data transfer class for materialized router port.
 * 
 * @version 1.6 18 Sept 2011
 * @author Ryu Ishimoto
 */
public class MaterializedRouterPort extends RouterPort {

    private String localNetworkAddress = null;
    private int localNetworkLength;

    public MaterializedRouterPort() {
        super();
    }

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

    @Override
    public PortConfig toConfig() {
        return new MaterializedRouterPortConfig(this.getDeviceId(), Net
                .convertStringAddressToInt(this.getNetworkAddress()), this
                .getNetworkLength(), Net.convertStringAddressToInt(this
                .getPortAddress()), new HashSet<Route>(), Net
                .convertStringAddressToInt(this.getLocalNetworkAddress()), this
                .getLocalNetworkLength(), new HashSet<BGP>());
    }

    public static Port createPort(UUID id, PortMgmtConfig mgmtConfig,
            MaterializedRouterPortConfig config) {
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