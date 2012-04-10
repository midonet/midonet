/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.data.dao.zookeeper;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.apache.zookeeper.Op;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.mgmt.data.dao.BgpDao;
import com.midokura.midolman.mgmt.data.dao.PortDao;
import com.midokura.midolman.mgmt.data.dao.VpnDao;
import com.midokura.midolman.mgmt.data.dto.Bgp;
import com.midokura.midolman.mgmt.data.dto.BridgePort;
import com.midokura.midolman.mgmt.data.dto.LogicalBridgePort;
import com.midokura.midolman.mgmt.data.dto.LogicalRouterPort;
import com.midokura.midolman.mgmt.data.dto.MaterializedRouterPort;
import com.midokura.midolman.mgmt.data.dto.Port;
import com.midokura.midolman.mgmt.data.dto.Vpn;
import com.midokura.midolman.mgmt.data.dto.config.PortMgmtConfig;
import com.midokura.midolman.mgmt.data.zookeeper.op.PortOpService;
import com.midokura.midolman.state.PortConfig;
import com.midokura.midolman.state.PortDirectory.LogicalRouterPortConfig;
import com.midokura.midolman.state.PortDirectory.MaterializedRouterPortConfig;
import com.midokura.midolman.state.StateAccessException;

/**
 * Port ZK DAO adapter.
 */
public class PortDaoAdapter implements PortDao {

    private final static Logger log = LoggerFactory
            .getLogger(PortDaoAdapter.class);
    private final PortZkDao zkDao;
    private final PortOpService opService;
    private final BgpDao bgpDao;
    private final VpnDao vpnDao;

    /**
     * Constructor
     *
     * @param zkDao
     *            PortZkDao object.
     * @param opService
     *            PortOpService object.
     * @param bgpDao
     *            BgpDao object.
     * @param vpnDao
     *            VpnDao object.
     */
    public PortDaoAdapter(PortZkDao zkDao, PortOpService opService,
            BgpDao bgpDao, VpnDao vpnDao) {
        this.zkDao = zkDao;
        this.opService = opService;
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
        log.debug("PortDaoAdapter.create entered: port={}", port);

        UUID id = UUID.randomUUID();
        port.setId(id);
        List<Op> ops = opService.buildCreate(port.getId(), port.toConfig(),
                port.toMgmtConfig());
        zkDao.multi(ops);

        log.debug("PortDaoAdapter.create exiting: port={}", port);
        return port.getId();
    }

    /*
     * (non-Javadoc)
     *
     * @see com.midokura.midolman.mgmt.data.dao.PortDao#delete(java.util.UUID)
     */
    @Override
    public void delete(UUID id) throws StateAccessException {
        log.debug("PortDaoAdapter.delete entered: id={}", id);

        Port port = get(id);

        // Don't let a port that has a vif plugged in get deleted.
        if (port.getVifId() != null) {
            throw new IllegalArgumentException(
                    "Cannot delete a port with VIF plugged in.");
        }

        if (port instanceof LogicalRouterPort
                || port instanceof LogicalBridgePort) {
            throw new UnsupportedOperationException(
                    "Cannot delete a logical port without deleting the link.");
        }

        List<Op> ops = opService.buildDelete(port.getId(), true);
        zkDao.multi(ops);

        log.debug("PortDaoAdapter.delete exiting.");
    }

    /*
     * (non-Javadoc)
     *
     * @see com.midokura.midolman.mgmt.data.dao.PortDao#exists(java.util.UUID)
     */
    @Override
    public boolean exists(UUID id) throws StateAccessException {
        log.debug("PortDaoAdapter.exists entered: id={}", id);
        boolean exists = zkDao.exists(id);
        log.debug("PortDaoAdapter.exists exiting: exists={}", exists);
        return exists;
    }

    /*
     * (non-Javadoc)
     *
     * @see com.midokura.midolman.mgmt.data.dao.PortDao#get(java.util.UUID)
     */
    @Override
    public Port get(UUID id) throws StateAccessException {
        log.debug("PortDaoAdapter.get entered: id={}", id);

        Port port = null;
        if (zkDao.exists(id)) {
            PortConfig config = zkDao.getData(id);
            if (config instanceof LogicalRouterPortConfig) {
                port = new LogicalRouterPort(id,
                        (LogicalRouterPortConfig) config);
            } else {
                PortMgmtConfig mgmtConfig = zkDao.getMgmtData(id);
                if (config instanceof MaterializedRouterPortConfig) {
                    port = new MaterializedRouterPort(id, mgmtConfig,
                            (MaterializedRouterPortConfig) config);
                } else {
                    port = new BridgePort(id, mgmtConfig, config);
                }
            }
        }

        log.debug("PortDaoAdapter.get existing: port={}", port);
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
        log.debug("PortDaoAdapter.getByAdRoute entered: adRouteId={}",
                adRouteId);

        Bgp bgp = bgpDao.getByAdRoute(adRouteId);
        Port port = get(bgp.getPortId());

        log.debug("PortDaoAdapter.getByAdRoute exiting: port={}", port);
        return port;
    }

    /*
     * (non-Javadoc)
     *
     * @see com.midokura.midolman.mgmt.data.dao.PortDao#getByBgp(java.util.UUID)
     */
    @Override
    public Port getByBgp(UUID bgpId) throws StateAccessException {
        log.debug("PortDaoAdapter.getByBgp entered: bgpId={}", bgpId);

        Bgp bgp = bgpDao.get(bgpId);
        Port port = get(bgp.getPortId());

        log.debug("PortDaoAdapter.getByBgp exiting: port={}", port);
        return port;
    }

    /*
     * (non-Javadoc)
     *
     * @see com.midokura.midolman.mgmt.data.dao.PortDao#getByVpn(java.util.UUID)
     */
    @Override
    public Port getByVpn(UUID vpnId) throws StateAccessException {
        log.debug("PortDaoAdapter.getByVpn entered: vpnId={}", vpnId);

        Vpn vpn = vpnDao.get(vpnId);
        Port port = get(vpn.getPublicPortId());

        log.debug("PortDaoAdapter.getByVpn exiting: port={}", port);
        return port;
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
        log.debug("PortDaoAdapter.listBridgePorts entered: bridgeId={}",
                bridgeId);

        Set<UUID> ids = zkDao.getBridgePortIds(bridgeId);
        List<Port> ports = new ArrayList<Port>();
        for (UUID id : ids) {
            ports.add(get(id));
        }

        log.debug("PortDaoAdapter.listBridgePorts exiting: port count={}",
                ports.size());
        return ports;
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
        log.debug("PortDaoAdapter.listRouterPorts entered: routerId={}",
                routerId);

        Set<UUID> ids = zkDao.getRouterPortIds(routerId);
        List<Port> ports = new ArrayList<Port>();
        for (UUID id : ids) {
            ports.add(get(id));
        }

        log.debug("PortDaoAdapter.listRouterPorts exiting: port count={}",
                ports.size());
        return ports;
    }
}
