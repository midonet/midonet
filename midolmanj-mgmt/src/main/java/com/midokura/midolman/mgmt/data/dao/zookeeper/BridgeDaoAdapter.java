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

import com.midokura.midolman.mgmt.data.dao.BridgeDao;
import com.midokura.midolman.mgmt.data.dao.PortDao;
import com.midokura.midolman.mgmt.data.dto.Bridge;
import com.midokura.midolman.mgmt.data.dto.BridgePort;
import com.midokura.midolman.mgmt.data.dto.Port;
import com.midokura.midolman.mgmt.data.dto.config.BridgeMgmtConfig;
import com.midokura.midolman.mgmt.data.zookeeper.op.BridgeOpService;
import com.midokura.midolman.state.BridgeZkManager.BridgeConfig;
import com.midokura.midolman.state.StateAccessException;

/**
 * Bridge ZK DAO adapter
 */
public class BridgeDaoAdapter implements BridgeDao {

    private final static Logger log = LoggerFactory
            .getLogger(BridgeDaoAdapter.class);
    private final BridgeZkDao zkDao;
    private final BridgeOpService opService;
    private final PortDao portDao;

    /**
     * Constructor
     *
     * @param zkDao
     *            BridgeZkDao object.
     * @param opService
     *            BridgeOpService object.
     * @param portDao
     *            PortDao object.
     */
    public BridgeDaoAdapter(BridgeZkDao zkDao, BridgeOpService opService,
            PortDao portDao) {
        this.zkDao = zkDao;
        this.opService = opService;
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
        log.debug("BridgeDaoAdapter.create entered: bridge={}", bridge);

        if (bridge.getId() == null) {
            bridge.setId(UUID.randomUUID());
        }

        List<Op> ops = opService.buildCreate(bridge.getId(), bridge.toConfig(),
                bridge.toMgmtConfig(), bridge.toNameMgmtConfig());
        zkDao.multi(ops);

        log.debug("BridgeDaoAdapter.create exiting: bridge={}", bridge);
        return bridge.getId();
    }

    /*
     * (non-Javadoc)
     *
     * @see com.midokura.midolman.mgmt.data.dao.BridgeDao#delete(java.util.UUID)
     */
    @Override
    public void delete(UUID id) throws StateAccessException {
        log.debug("BridgeDaoAdapter.delete entered: id={}", id);

        List<Op> ops = opService.buildDelete(id, true);
        zkDao.multi(ops);

        log.debug("BridgeDaoAdapter.delete exiting.");
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * com.midokura.midolman.mgmt.data.dao.BridgeDao#getByPort(java.util.UUID)
     */
    @Override
    public Bridge getByPort(UUID portId) throws StateAccessException {
        log.debug("BridgeDaoAdapter.getByPort entered: portId={}", portId);

        Port port = portDao.get(portId);
        if (!(port instanceof BridgePort)) {
            return null;
        }
        Bridge bridge = get(port.getDeviceId());

        log.debug("BridgeDaoAdapter.getByPort exiting: bridge={}", bridge);
        return bridge;
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
        log.debug("BridgeDaoAdapter.update entered: bridge={}", bridge);

        List<Op> ops = opService.buildUpdate(bridge.getId(), bridge.getName());
        zkDao.multi(ops);

        log.debug("BridgeDaoAdapter.update exiting");
    }

    /*
     * (non-Javadoc)
     *
     * @see com.midokura.midolman.mgmt.data.dao.BridgeDao#get(java.util.UUID)
     */
    @Override
    public Bridge get(UUID id) throws StateAccessException {
        log.debug("BridgeDaoAdapter.get entered: id={}", id);

        Bridge bridge = null;
        if (zkDao.exists(id)) {
            BridgeMgmtConfig mgmtConfig = zkDao.getMgmtData(id);
            BridgeConfig config = zkDao.getData(id);
            bridge = new Bridge(id, mgmtConfig, config);
        }

        log.debug("BridgeDaoAdapter.get exiting: bridge={}", bridge);
        return bridge;
    }

    /*
     * (non-Javadoc)
     *
     * @see com.midokura.midolman.mgmt.data.dao.BridgeDao#list(java.lang.String)
     */
    @Override
    public List<Bridge> list(String tenantId) throws StateAccessException {
        log.debug("BridgeDaoAdapter.list entered: tenantId={}", tenantId);

        Set<String> ids = zkDao.getIds(tenantId);
        List<Bridge> bridges = new ArrayList<Bridge>();
        for (String id : ids) {
            bridges.add(get(UUID.fromString(id)));
        }

        log.debug("BridgeDaoAdapter.list exiting: bridges count={}",
                bridges.size());
        return bridges;
    }
}
