/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.data.zookeeper.dao;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.google.inject.Inject;
import com.midokura.midolman.mgmt.data.dto.ConfigProperty;
import org.apache.zookeeper.Op;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.mgmt.data.dao.PortDao;
import com.midokura.midolman.mgmt.data.dto.Bridge;
import com.midokura.midolman.mgmt.data.dto.BridgePort;
import com.midokura.midolman.mgmt.data.dto.Port;
import com.midokura.midolman.mgmt.data.dto.config.BridgeNameMgmtConfig;
import com.midokura.midolman.state.PathBuilder;
import com.midokura.midolman.state.zkManagers.BridgeZkManager;
import com.midokura.midolman.state.zkManagers.BridgeZkManager.BridgeConfig;
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
    @Inject
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

    @Override
    public List<Op> prepareDelete(UUID id) throws StateAccessException {
        return prepareDelete(get(id));
    }

    @Override
    public List<Op> prepareDelete(Bridge bridge) throws StateAccessException {

        List<Op> ops = zkDao.prepareBridgeDelete(bridge.getId());

        String path = pathBuilder.getTenantBridgeNamePath(bridge.getTenantId(),
                bridge.getName());
        ops.add(zkDao.getDeleteOp(path));

        return ops;
    }

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

            String tenantId = oldConfig.properties.get(
                    ConfigProperty.TENANT_ID);

            String path = pathBuilder.getTenantBridgeNamePath(tenantId,
                    oldConfig.name);
            ops.add(zkDao.getDeleteOp(path));

            path = pathBuilder.getTenantBridgeNamePath(tenantId,
                    bridge.getName());
            byte[] data = serializer.serialize(bridge.toNameMgmtConfig());
            ops.add(zkDao.getPersistentCreateOp(path, data));
        }

        if (ops.size() > 0) {
            zkDao.multi(ops);
        }

        log.debug("BridgeZkDaoImpl.update exiting");
    }

    @Override
    public Bridge findByName(String tenantId, String name)
            throws StateAccessException {
        log.debug("BridgeZkDaoImpl.findByName entered: tenantId=" + tenantId
                + ", name=" + name);

        Bridge bridge = null;
        String path = pathBuilder.getTenantBridgeNamePath(tenantId, name);
        if (zkDao.exists(path)) {
            byte[] data = zkDao.get(path);
            BridgeNameMgmtConfig nameConfig = serializer.deserialize(data,
                    BridgeNameMgmtConfig.class);
            bridge = get(nameConfig.id);
        }

        log.debug("BridgeZkDaoImpl.findByName exiting: bridge={}", bridge);
        return bridge;
    }

    @Override
    public Bridge findByPort(UUID portId) throws StateAccessException {
        log.debug("BridgeZkDaoImpl.findByPort entered: portId={}", portId);

        Port port = portDao.get(portId);
        if (!(port instanceof BridgePort)) {
            return null;
        }
        Bridge bridge = get(port.getDeviceId());

        log.debug("BridgeZkDaoImpl.findByPort exiting: bridge={}", bridge);
        return bridge;
    }

    @Override
    public List<Bridge> findByTenant(String tenantId) throws StateAccessException {
        log.debug("BridgeZkDaoImpl.findByTenant entered: tenantId={}",
                tenantId);

        String path = pathBuilder.getTenantBridgeNamesPath(tenantId);
        Set<String> names = zkDao.getChildren(path, null);
        List<Bridge> bridges = new ArrayList<Bridge>();
        for (String name : names) {
            bridges.add(findByName(tenantId, name));
        }

        log.debug("BridgeZkDaoImpl.findByTenant exiting: bridges count={}",
                bridges.size());
        return bridges;
    }
}
