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

import com.midokura.midolman.mgmt.data.dao.PortDao;
import com.midokura.midolman.mgmt.data.dto.Bridge;
import com.midokura.midolman.mgmt.data.dto.BridgePort;
import com.midokura.midolman.mgmt.data.dto.Port;
import com.midokura.midolman.mgmt.data.zookeeper.path.PathBuilder;
import com.midokura.midolman.state.BridgeZkManager;
import com.midokura.midolman.state.BridgeZkManager.BridgeConfig;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midolman.state.ZkConfigSerializer;

/**
 * Bridge ZK DAO implementation
 */
public class BridgeZkDaoImpl implements BridgeZkDao {

    private final static Logger log = LoggerFactory
            .getLogger(BridgeZkDaoImpl.class);
    private final BridgeZkManager zkDao;
    private final PathBuilder pathBuilder;
    private final ZkConfigSerializer serializer;
    private final PortDao portDao;

    /**
     * Constructor
     *
     * @param zkDao
     *            BridgeZkManager object.
     * @param pathBuilder
     *            PathBuilder object to get path data.
     * @param serializer
     *            ZkConfigSerializer object.
     * @param portDao
     *            PortDao object.
     */
    public BridgeZkDaoImpl(BridgeZkManager zkDao, PathBuilder pathBuilder,
            ZkConfigSerializer serializer, PortDao portDao) {
        this.zkDao = zkDao;
        this.pathBuilder = pathBuilder;
        this.serializer = serializer;
        this.portDao = portDao;
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * com.midokura.midolman.mgmt.data.dao.BridgeDao#create(com.midokura.midolman
     * .mgmt.data.dto.Bridge)
     */
    @Override
    public UUID create(Bridge bridge) throws StateAccessException {
        log.debug("BridgeZkDaoImpl.create entered: bridge={}", bridge);

        if (bridge.getId() == null) {
            bridge.setId(UUID.randomUUID());
        }

        List<Op> ops = zkDao.prepareBridgeCreate(bridge.getId(),
                bridge.toConfig());

        ops.add(zkDao.getPersistentCreateOp(
                pathBuilder.getTenantBridgePath(bridge.getTenantId(),
                        bridge.getId()), null));

        byte[] data = serializer.serialize(bridge.toNameMgmtConfig());
        ops.add(zkDao.getPersistentCreateOp(
                pathBuilder.getTenantBridgeNamePath(bridge.getTenantId(),
                        bridge.getName()), data));

        zkDao.multi(ops);

        log.debug("BridgeZkDaoImpl.create exiting: bridge={}", bridge);
        return bridge.getId();
    }

    /*
     * (non-Javadoc)
     *
     * @see com.midokura.midolman.mgmt.data.dao.BridgeDao#delete(java.util.UUID)
     */
    @Override
    public void delete(UUID id) throws StateAccessException {
        log.debug("BridgeZkDaoImpl.delete entered: id={}", id);

        List<Op> ops = prepareDelete(id);
        zkDao.multi(ops);

        log.debug("BridgeZkDaoImpl.delete exiting.");
    }

    /*
     * (non-Javadoc)
     *
     * @see com.midokura.midolman.mgmt.data.dao.BridgeDao#get(java.util.UUID)
     */
    @Override
    public Bridge get(UUID id) throws StateAccessException {
        log.debug("BridgeZkDaoImpl.get entered: id={}", id);

        Bridge bridge = null;
        if (zkDao.exists(id)) {
            BridgeConfig config = zkDao.get(id);
            bridge = new Bridge(id, config);
        }

        log.debug("BridgeZkDaoImpl.get exiting: bridge={}", bridge);
        return bridge;
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * com.midokura.midolman.mgmt.data.dao.BridgeDao#getByName(java.lang.String,
     * java.lang.String)
     */
    @Override
    public Bridge getByName(String tenantId, String name)
            throws StateAccessException {
        log.debug("BridgeZkDaoImpl.getByName entered: tenantId=" + tenantId
                + ", name=" + name);

        List<Bridge> bridges = list(tenantId);
        Bridge match = null;
        for (Bridge bridge : bridges) {
            if (bridge.getName().equals(name)) {
                match = bridge;
                break;
            }
        }

        log.debug("BridgeZkDaoImpl.getByName exiting: bridge={}", match);
        return match;
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * com.midokura.midolman.mgmt.data.dao.BridgeDao#getByPort(java.util.UUID)
     */
    @Override
    public Bridge getByPort(UUID portId) throws StateAccessException {
        log.debug("BridgeZkDaoImpl.getByPort entered: portId={}", portId);

        Port port = portDao.get(portId);
        if (!(port instanceof BridgePort)) {
            return null;
        }
        Bridge bridge = get(port.getDeviceId());

        log.debug("BridgeZkDaoImpl.getByPort exiting: bridge={}", bridge);
        return bridge;
    }

    /*
     * (non-Javadoc)
     *
     * @see com.midokura.midolman.mgmt.data.dao.BridgeDao#list(java.lang.String)
     */
    @Override
    public List<Bridge> list(String tenantId) throws StateAccessException {
        log.debug("BridgeZkDaoImpl.list entered: tenantId={}", tenantId);

        String path = pathBuilder.getTenantBridgesPath(tenantId);
        Set<String> ids = zkDao.getChildren(path, null);
        List<Bridge> bridges = new ArrayList<Bridge>();
        for (String id : ids) {
            bridges.add(get(UUID.fromString(id)));
        }

        log.debug("BridgeZkDaoImpl.list exiting: bridges count={}",
                bridges.size());
        return bridges;
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * com.midokura.midolman.mgmt.data.dao.zookeeper.BridgeZkDao#prepareDelete
     * (java.util.UUID)
     */
    @Override
    public List<Op> prepareDelete(UUID id) throws StateAccessException {
        return prepareDelete(get(id));
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * com.midokura.midolman.mgmt.data.dao.zookeeper.BridgeZkDao#prepareDelete
     * (com.midokura.midolman.mgmt.data.dto.Bridge)
     */
    @Override
    public List<Op> prepareDelete(Bridge bridge) throws StateAccessException {

        List<Op> ops = zkDao.prepareBridgeDelete(bridge.getId());
        String path = pathBuilder.getTenantBridgePath(bridge.getTenantId(),
                bridge.getId());
        ops.add(zkDao.getDeleteOp(path));

        path = pathBuilder.getTenantBridgeNamePath(bridge.getTenantId(),
                bridge.getName());
        ops.add(zkDao.getDeleteOp(path));

        return ops;
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * com.midokura.midolman.mgmt.data.dao.BridgeDao#update(com.midokura.midolman
     * .mgmt.data.dto.Bridge)
     */
    @Override
    public void update(Bridge bridge) throws StateAccessException {
        log.debug("BridgeZkDaoImpl.update entered: bridge={}", bridge);

        List<Op> ops = new ArrayList<Op>();

        // Get the original config
        BridgeConfig oldConfig = zkDao.get(bridge.getId());

        // Update the config
        Op op = zkDao.prepareUpdate(bridge.getId(), bridge.toConfig());
        if (op != null) {
            ops.add(op);
        }

        // Update index if the name changed
        if (!bridge.getName().equals(oldConfig.name)) {

            String path = pathBuilder.getTenantBridgeNamePath(
                    bridge.getTenantId(), oldConfig.name);
            ops.add(zkDao.getDeleteOp(path));

            path = pathBuilder.getTenantBridgeNamePath(bridge.getTenantId(),
                    bridge.getName());
            byte[] data = serializer.serialize(bridge.toNameMgmtConfig());
            ops.add(zkDao.getPersistentCreateOp(path, data));
        }

        if (ops.size() > 0) {
            zkDao.multi(ops);
        }

        log.debug("BridgeZkDaoImpl.update exiting");
    }
}
