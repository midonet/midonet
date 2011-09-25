/*
 * @(#)VifDataAccessor        1.6 11/09/24
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.dao;

import java.util.UUID;

import com.midokura.midolman.mgmt.data.ZookeeperService;
import com.midokura.midolman.mgmt.data.dto.Vif;
import com.midokura.midolman.mgmt.data.state.VifZkManager;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midolman.state.ZkConnection;
import com.midokura.midolman.state.ZkStateSerializationException;

/**
 * Data access class for VIF.
 * 
 * @version 1.6 24 Sept 2011
 * @author Ryu Ishimoto
 */
public class VifDataAccessor extends DataAccessor {

    /*
     * Implements CRUD operations on VIF.
     */

    /**
     * Constructor
     * 
     * @param zkConn
     *            Zookeeper connection string
     */
    public VifDataAccessor(String zkConn, int timeout, String rootPath,
            String mgmtRootPath) {
        super(zkConn, timeout, rootPath, mgmtRootPath);
    }

    private VifZkManager getVifZkManager() throws Exception {
        ZkConnection conn = ZookeeperService.getConnection(zkConn, zkTimeout);
        return new VifZkManager(conn.getZooKeeper(), zkRoot, zkMgmtRoot);
    }

    public UUID create(Vif vif) throws Exception {
        return getVifZkManager().create(vif);
    }

    public Vif get(UUID id) throws StateAccessException,
            ZkStateSerializationException, Exception {
        return getVifZkManager().get(id);
    }

    public void delete(UUID id) throws Exception {
        getVifZkManager().delete(id);
    }
}
