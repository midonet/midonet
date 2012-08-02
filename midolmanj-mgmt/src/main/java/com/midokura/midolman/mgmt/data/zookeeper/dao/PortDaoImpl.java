/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.data.zookeeper.dao;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.midokura.midolman.state.zkManagers.PortZkManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.mgmt.data.dao.BgpDao;
import com.midokura.midolman.mgmt.data.dao.PortDao;
import com.midokura.midolman.mgmt.data.dao.VpnDao;
import com.midokura.midolman.mgmt.data.dto.Bgp;
import com.midokura.midolman.mgmt.data.dto.ConfigProperty;
import com.midokura.midolman.mgmt.data.dto.Port;
import com.midokura.midolman.mgmt.data.dto.PortFactory;
import com.midokura.midolman.mgmt.data.dto.Vpn;
import com.midokura.midolman.state.PortConfig;
import com.midokura.midolman.state.StateAccessException;

/**
 * Port ZK DAO adapter.
 */
public class PortDaoImpl implements PortDao {

    private final static Logger log = LoggerFactory
            .getLogger(PortDaoImpl.class);
    private final PortZkManager zkDao;
    private final BgpDao bgpDao;
    private final VpnDao vpnDao;

    private List<Port> getPeerLogicalPorts(List<Port> ports)
            throws StateAccessException {
        List<Port> logicalPorts = new ArrayList<Port>();
        for (Port port : ports) {
            if (port.isLogical() && port.hasAttachment()) {
                logicalPorts.add(get(port.getAttachmentId()));
            }
        }
        return logicalPorts;
    }

    private void updateLinkPorts(UUID id, PortConfig config, UUID peerId,
            PortConfig peerConfig) throws StateAccessException {
        Map<UUID, PortConfig> ports = new HashMap<UUID, PortConfig>(2);
        ports.put(id, config);
        ports.put(peerId, peerConfig);
        zkDao.update(ports);
    }

    /**
     * Constructor
     *
     * @param zkDao
     *            PortZkManager object.
     * @param bgpDao
     *            BgpDao object.
     * @param vpnDao
     *            VpnDao object.
     */
    public PortDaoImpl(PortZkManager zkDao, BgpDao bgpDao, VpnDao vpnDao) {
        this.zkDao = zkDao;
        this.bgpDao = bgpDao;
        this.vpnDao = vpnDao;
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * com.midokura.midolman.mgmt.data.dao.PortDao#create(com.midokura.midolman
     * .mgmt.data.dto.Port)
     */
    @Override
    public UUID create(Port port) throws StateAccessException {
        log.debug("PortDaoImpl.create entered: port={}", port);

        // Don't create a port with any attachment
        port.setAttachmentId(null);
        UUID id = zkDao.create(port.toConfig());

        log.debug("PortDaoImpl.create exiting: id={}", id);
        return id;
    }

    /*
     * (non-Javadoc)
     *
     * @see com.midokura.midolman.mgmt.data.dao.PortDao#delete(java.util.UUID)
     */
    @Override
    public void delete(UUID id) throws StateAccessException, PortInUseException {
        log.debug("PortDaoImpl.delete entered: id={}", id);

        Port port = get(id);

        // Don't let a port that has if plugged in..
        if (port.hasAttachment()) {
            throw new PortInUseException("Cannot delete a port being used.");
        }

        zkDao.delete(port.getId());

        log.debug("PortDaoImpl.delete exiting.");
    }

    /*
     * (non-Javadoc)
     *
     * @see com.midokura.midolman.mgmt.data.dao.PortDao#exists(java.util.UUID)
     */
    @Override
    public boolean exists(UUID id) throws StateAccessException {
        log.debug("PortDaoImpl.exists entered: id={}", id);

        boolean exists = zkDao.exists(id);

        log.debug("PortDaoImpl.exists exiting: exists={}", exists);
        return exists;
    }

    /*
     * (non-Javadoc)
     *
     * @see com.midokura.midolman.mgmt.data.dao.PortDao#get(java.util.UUID)
     */
    @Override
    public Port get(UUID id) throws StateAccessException {
        log.debug("PortDaoImpl.get entered: id={}", id);

        Port port = null;
        if (zkDao.exists(id)) {
            PortConfig config = zkDao.get(id);
            port = PortFactory.createPort(id, config);
        }

        log.debug("PortDaoImpl.get existing: port={}", port);
        return port;
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * com.midokura.midolman.mgmt.data.dao.PortDao#getByAdRoute(java.util.UUID)
     */
    @Override
    public Port getByAdRoute(UUID adRouteId) throws StateAccessException {
        log.debug("PortDaoImpl.getByAdRoute entered: adRouteId={}",
                adRouteId);

        Bgp bgp = bgpDao.getByAdRoute(adRouteId);
        Port port = get(bgp.getPortId());

        log.debug("PortDaoImpl.getByAdRoute exiting: port={}", port);
        return port;
    }

    /*
     * (non-Javadoc)
     *
     * @see com.midokura.midolman.mgmt.data.dao.PortDao#getByBgp(java.util.UUID)
     */
    @Override
    public Port getByBgp(UUID bgpId) throws StateAccessException {
        log.debug("PortDaoImpl.getByBgp entered: bgpId={}", bgpId);

        Bgp bgp = bgpDao.get(bgpId);
        Port port = get(bgp.getPortId());

        log.debug("PortDaoImpl.getByBgp exiting: port={}", port);
        return port;
    }

    /*
     * (non-Javadoc)
     *
     * @see com.midokura.midolman.mgmt.data.dao.PortDao#getByVpn(java.util.UUID)
     */
    @Override
    public Port getByVpn(UUID vpnId) throws StateAccessException {
        log.debug("PortDaoImpl.getByVpn entered: vpnId={}", vpnId);

        Vpn vpn = vpnDao.get(vpnId);
        Port port = get(vpn.getPublicPortId());

        log.debug("PortDaoImpl.getByVpn exiting: port={}", port);
        return port;
    }

    /*
     * (non-Javadoc)
     *
     * @see com.midokura.midolman.mgmt.data.dao.PortDao#link(java.util.UUID,
     * java.util.UUID)
     */
    @Override
    public void link(UUID id, UUID peerId) throws StateAccessException,
            PortInUseException {
        log.debug("PortDaoImpl.link entered: id=" + id + ", peerId="
                + peerId);

        if (id == null || peerId == null) {
            throw new IllegalArgumentException("Null port IDs passed in");
        }

        // Get two ports
        Port port = get(id);
        if (port == null) {
            throw new IllegalArgumentException("No port found with ID " + id);
        }

        Port peerPort = get(peerId);
        if (peerPort == null) {
            throw new IllegalArgumentException("No port found with ID "
                    + peerPort);
        }

        if (port.hasAttachment() || peerPort.hasAttachment()) {
            throw new PortInUseException(
                    "At least one of the ports already linked.");
        }

        if (!port.isLinkable(peerPort)) {
            throw new IllegalArgumentException("Non-linkable ports passed in.");
        }

        // Set peer IDs
        port.setAttachmentId(peerId);
        peerPort.setAttachmentId(id);

        // Get the multi operations to link
        updateLinkPorts(id, port.toConfig(), peerId, peerPort.toConfig());

        log.debug("PortDaoImpl.link exiting");
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * com.midokura.midolman.mgmt.data.dao.PortDao#listBridgePorts(java.util
     * .UUID)
     */
    @Override
    public List<Port> listBridgePorts(UUID bridgeId)
            throws StateAccessException {
        log.debug("PortDaoImpl.listBridgePorts entered: bridgeId={}",
                bridgeId);

        Set<UUID> ids = zkDao.getBridgePortIDs(bridgeId);
        List<Port> ports = new ArrayList<Port>();
        for (UUID id : ids) {
            ports.add(get(id));
        }

        ids = zkDao.getBridgeLogicalPortIDs(bridgeId);
        for (UUID id : ids) {
            ports.add(get(id));
        }

        log.debug("PortDaoImpl.listBridgePorts exiting: port count={}",
                ports.size());
        return ports;
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * com.midokura.midolman.mgmt.data.dao.PortDao#listBridgePeerPorts(java.
     * util.UUID)
     */
    @Override
    public List<Port> listBridgePeerPorts(UUID bridgeId)
            throws StateAccessException {
        log.debug("PortDaoImpl.listBridgePeerPorts entered: bridgeId={}",
                bridgeId);

        Set<UUID> ids = zkDao.getBridgeLogicalPortIDs(bridgeId);
        List<Port> ports = new ArrayList<Port>();
        for (UUID id : ids) {
            ports.add(get(id));
        }

        List<Port> logicalPorts = getPeerLogicalPorts(ports);

        log.debug("PortDaoImpl.listBridgePeerPorts exiting: port count={}",
                logicalPorts.size());
        return logicalPorts;
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * com.midokura.midolman.mgmt.data.dao.PortDao#listRouterPorts(java.util
     * .UUID)
     */
    @Override
    public List<Port> listRouterPorts(UUID routerId)
            throws StateAccessException {
        log.debug("PortDaoImpl.listRouterPorts entered: routerId={}",
                routerId);

        Set<UUID> ids = zkDao.getRouterPortIDs(routerId);
        List<Port> ports = new ArrayList<Port>();
        for (UUID id : ids) {
            ports.add(get(id));
        }

        log.debug("PortDaoImpl.listRouterPorts exiting: port count={}",
                ports.size());
        return ports;
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * com.midokura.midolman.mgmt.data.dao.PortDao#listRouterPeerPorts(java.
     * util.UUID)
     */
    @Override
    public List<Port> listRouterPeerPorts(UUID routerId)
            throws StateAccessException {
        log.debug("PortDaoImpl.listRouterPeerPorts entered: routerId={}",
                routerId);

        // TODO: Find more efficient way to do this.
        List<Port> ports = listRouterPorts(routerId);
        List<Port> logicalPorts = getPeerLogicalPorts(ports);

        log.debug("PortDaoImpl.listRouterPeerPorts exiting: port count={}",
                logicalPorts.size());
        return logicalPorts;
    }

    /*
     * (non-Javadoc)
     *
     * @see com.midokura.midolman.mgmt.data.dao.PortDao#unlink(java.util.UUID)
     */
    @Override
    public void unlink(UUID id) throws StateAccessException {
        log.debug("PortDaoImpl.unlink entered: id={}", id);

        if (id == null) {
            throw new IllegalArgumentException("Null ID passed in");
        }

        // Get the port
        Port port = get(id);
        if (port == null) {
            throw new IllegalArgumentException("No port found with ID " + id);
        }

        // If this isn't a logical port, throw an exception.
        if (!port.isLogical()) {
            throw new IllegalArgumentException(id + " is not a logical port.");
        }

        // If already unlinked, don't do anything
        if (!port.hasAttachment()) {
            return;
        }

        // Get the peer port
        Port peerPort = get(port.getAttachmentId());

        // Unset the peer IDs
        port.setAttachmentId(null);
        peerPort.setAttachmentId(null);

        // Get unlink ops
        updateLinkPorts(id, port.toConfig(), peerPort.getId(),
                peerPort.toConfig());

        log.debug("PortDaoImpl.unlink exiting");
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * com.midokura.midolman.mgmt.data.dao.PortDao#update(com.midokura.midolman
     * .mgmt.data.dto.Port)
     */
    @Override
    public void update(Port port) throws StateAccessException {
        log.debug("PortDaoImpl.update entered: port={}", port);

        PortConfig config = zkDao.get(port.getId());
        config.inboundFilter = port.getInboundFilterId();
        config.outboundFilter = port.getOutboundFilterId();
        if (port.getPortGroupIDs() != null) {
            config.portGroupIDs = new HashSet<UUID>(Arrays.asList(port
                    .getPortGroupIDs()));
        } else {
            config.portGroupIDs = null;
        }

        if (port.getAttachmentId() != null) {
            config.properties.put(ConfigProperty.VIF_ID, port.getAttachmentId()
                    .toString());
        } else {
            // Unplug
            config.properties.remove(ConfigProperty.VIF_ID);
        }

        zkDao.update(port.getId(), config);

        log.debug("PortDaoImpl.update exiting");
    }

}
