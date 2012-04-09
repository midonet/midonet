/*
 * @(#)LogicalRouterPort        1.6 18/09/05
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.dto;

import java.util.HashSet;
import java.util.UUID;

import javax.xml.bind.annotation.XmlRootElement;

import com.midokura.midolman.layer3.Route;
import com.midokura.midolman.mgmt.data.dto.config.PeerRouterConfig;
import com.midokura.midolman.state.PortConfig;
import com.midokura.midolman.state.PortDirectory;
import com.midokura.midolman.state.PortDirectory.LogicalRouterPortConfig;
import com.midokura.midolman.state.ZkNodeEntry;
import com.midokura.midolman.util.Net;

/**
 * Data transfer class for logical router port.
 *
 * @version 1.6 18 Sept 2011
 * @author Ryu Ishimoto
 */
@XmlRootElement
public class LogicalRouterPort extends RouterPort {

    /**
     * Peer port ID address
     */
    protected String peerPortAddress = null;

    /**
     * Peer router ID
     */
    protected UUID peerRouterId = null;

    /**
     * Peer port ID
     */
    protected UUID peerId = null;

    /**
     * Constructor
     */
    public LogicalRouterPort() {
        super();
    }

    /**
     * Constructor
     *
     * @param id
     *            ID of the port
     * @param config
     *            LogicalRouterPortConfig object
     */
    public LogicalRouterPort(UUID id, LogicalRouterPortConfig config) {
        this(id, config.device_id);
        this.networkAddress = Net.convertIntAddressToString(config.nwAddr);
        this.networkLength = config.nwLength;
        this.portAddress = Net.convertIntAddressToString(config.portAddr);
        this.peerId = config.peer_uuid;
    }

    /**
     * Constructor
     *
     * @param id
     *            ID of the port
     * @param deviceId
     *            Router ID
     */
    public LogicalRouterPort(UUID id, UUID deviceId) {
        super(id, deviceId, null);
    }

    /**
     * @return the peerId
     */
    public UUID getPeerId() {
        return peerId;
    }

    /**
     * @param peerId
     *            the peerId to set
     */
    public void setPeerId(UUID peerId) {
        this.peerId = peerId;
    }

    /**
     * @return the peerPortAddress
     */
    public String getPeerPortAddress() {
        return peerPortAddress;
    }

    /**
     * @param peerPortAddress
     *            the peerPortAddress to set
     */
    public void setPeerPortAddress(String peerPortAddress) {
        this.peerPortAddress = peerPortAddress;
    }

    /**
     * @return the peerRouterId
     */
    public UUID getPeerRouterId() {
        return peerRouterId;
    }

    /**
     * @param peerRouterId
     *            the peerRouterId to set
     */
    public void setPeerRouterId(UUID peerRouterId) {
        this.peerRouterId = peerRouterId;
    }

    /*
     * (non-Javadoc)
     *
     * @see com.midokura.midolman.mgmt.data.dto.Port#toConfig()
     */
    @Override
    public PortConfig toConfig() {
        return new PortDirectory.LogicalRouterPortConfig(this.getDeviceId(),
                Net.convertStringAddressToInt(this.getNetworkAddress()),
                this.getNetworkLength(), Net.convertStringAddressToInt(this
                        .getPortAddress()), new HashSet<Route>(),
                this.getPeerId(), null);
    }

    /**
     * @return PortConfig object of the peer.
     */
    public PortConfig toPeerConfig() {
        return new PortDirectory.LogicalRouterPortConfig(
                this.getPeerRouterId(), Net.convertStringAddressToInt(this
                        .getNetworkAddress()), this.getNetworkLength(),
                Net.convertStringAddressToInt(this.getPeerPortAddress()),
                new HashSet<Route>(), this.getId(), null);
    }

    /**
     * @return PeerRouterConfig object.
     */
    public PeerRouterConfig toPeerRouterConfig() {
        return new PeerRouterConfig(this.getId(), this.getPeerId());
    }

    /**
     * @return the PeerRouterConfig object of the peer.
     */
    public PeerRouterConfig toPeerPeerRouterConfig() {
        return new PeerRouterConfig(this.getPeerId(), this.getId());
    }

    /**
     * @return PeerRouterLink object for the peer.
     */
    public PeerRouterLink toPeerRouterLink() {
        PeerRouterLink link = new PeerRouterLink();
        link.setPortId(this.getId());
        link.setPeerPortId(peerId);
        link.setRouterId(this.getDeviceId());
        link.setPeerRouterId(peerRouterId);
        return link;
    }

    /*
     * (non-Javadoc)
     *
     * @see com.midokura.midolman.mgmt.data.dto.Port#toZkNode()
     */
    @Override
    public ZkNodeEntry<UUID, PortConfig> toZkNode() {
        return new ZkNodeEntry<UUID, PortConfig>(this.getId(), toConfig());
    }

    /**
     * @return ZkNodeEntry object for the peer port node.
     */
    public ZkNodeEntry<UUID, PortConfig> toPeerZkNode() {
        return new ZkNodeEntry<UUID, PortConfig>(this.getPeerId(),
                toPeerConfig());
    }

    /*
     * (non-Javadoc)
     *
     * @see com.midokura.midolman.mgmt.data.dto.Port#getType()
     */
    @Override
    public PortType getType() {
        return PortType.LOGICAL_ROUTER;
    }

    /*
     * (non-Javadoc)
     *
     * @see java.lang.Object#toString()
     */
    @Override
    public String toString() {
        return super.toString() + ", peerPortAddress=" + peerPortAddress
                + ", peerRouterId=" + peerRouterId + ", peerId=" + peerId;
    }
}
