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
import com.midokura.midolman.mgmt.rest_api.core.ResourceUriBuilder;
import com.midokura.midolman.state.BGP;
import com.midokura.midolman.state.PortConfig;
import com.midokura.midolman.state.PortDirectory;
import com.midokura.midolman.state.PortDirectory.MaterializedRouterPortConfig;
import com.midokura.midolman.state.ZkNodeEntry;
import com.midokura.midolman.util.Net;

/**
 * Data transfer class for materialized router port.
 *
 * @version 1.6 18 Sept 2011
 * @author Ryu Ishimoto
 */
@XmlRootElement
public class MaterializedRouterPort extends RouterPort {

    protected String localNetworkAddress = null;
    protected int localNetworkLength;

    /**
     * Constructor
     */
    public MaterializedRouterPort() {
        super();
    }

    /**
     * Constructor
     *
     * @param id
     *            ID of the port
     * @param mgmtConfig
     *            PortMgmtConfig object
     * @param config
     *            MaterializedRouterPortConfig object
     */
    public MaterializedRouterPort(UUID id, PortMgmtConfig mgmtConfig,
            MaterializedRouterPortConfig config) {
        this(id, config.device_id, mgmtConfig.vifId);
        this.localNetworkAddress = Net
                .convertIntAddressToString(config.localNwAddr);
        this.localNetworkLength = config.localNwLength;
        this.networkAddress = Net.convertIntAddressToString(config.nwAddr);
        this.networkLength = config.nwLength;
        this.portAddress = Net.convertIntAddressToString(config.portAddr);
    }

    /**
     * Constructor
     *
     * @param id
     *            ID of the port
     * @param deviceId
     *            ID of the router
     * @param vifId
     *            ID of the VIF.
     */
    public MaterializedRouterPort(UUID id, UUID deviceId, UUID vifId) {
        super(id, deviceId, vifId);
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

    /**
     * @return the bgps URI
     */
    public URI getBgps() {
        return ResourceUriBuilder.getPortBgps(getBaseUri(), this.getId());
    }

    /**
     * @return the vpns URI
     */
    public URI getVpns() {
        return ResourceUriBuilder.getPortVpns(getBaseUri(), this.getId());
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

    /*
     * (non-Javadoc)
     *
     * @see com.midokura.midolman.mgmt.data.dto.Port#toZkNode()
     */
    @Override
    public ZkNodeEntry<UUID, PortConfig> toZkNode() {
        return new ZkNodeEntry<UUID, PortConfig>(id, toConfig());
    }

    /*
     * (non-Javadoc)
     *
     * @see com.midokura.midolman.mgmt.data.dto.Port#getType()
     */
    @Override
    public PortType getType() {
        return PortType.MATERIALIZED_ROUTER;
    }

    /*
     * (non-Javadoc)
     *
     * @see java.lang.Object#toString()
     */
    @Override
    public String toString() {
        return super.toString() + ", localNetworkAddress="
                + localNetworkAddress + ", localNetworkLength="
                + localNetworkLength;
    }

}