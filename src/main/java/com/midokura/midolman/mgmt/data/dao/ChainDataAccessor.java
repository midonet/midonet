/*
 * @(#)ChainDataAccessor        1.6 11/09/05
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.dao;

import java.util.List;
import java.util.UUID;

import com.midokura.midolman.mgmt.data.ZookeeperService;
import com.midokura.midolman.mgmt.data.dto.Chain;
import com.midokura.midolman.mgmt.data.state.ChainZkManagerProxy;
import com.midokura.midolman.state.ZkConnection;

/**
 * Data access class for chains.
 * 
 * @version 1.6 08 Sept 2011
 * @author Ryu Ishimoto
 */
public class ChainDataAccessor extends DataAccessor {

    /**
     * Constructor
     * 
     * @param zkConn
     *            Zookeeper connection string
     */
    public ChainDataAccessor(String zkConn, int timeout, String rootPath,
            String mgmtRootPath) {
        super(zkConn, timeout, rootPath, mgmtRootPath);
    }

    private ChainZkManagerProxy getChainZkManager() throws Exception {
        ZkConnection conn = ZookeeperService.getConnection(zkConn, zkTimeout);
        return new ChainZkManagerProxy(conn.getZooKeeper(), zkRoot, zkMgmtRoot);
    }

    public UUID create(Chain chain) throws Exception {
        return getChainZkManager().create(chain);
    }

    public Chain get(UUID id) throws Exception {
        // TODO: Throw NotFound exception here.
        return getChainZkManager().get(id);
    }

    public List<Chain> list(UUID routerId) throws Exception {
        return getChainZkManager().list(routerId);
        // return chains.toArray(new Chain[chains.size()]);
    }

    public List<Chain> list(UUID routerId, String table) throws Exception {
        return getChainZkManager().listTableChains(routerId, table);
    }

    public void delete(UUID id) throws Exception {
        ChainZkManagerProxy manager = getChainZkManager();
        // TODO: catch NoNodeException if does not exist.
        manager.delete(id);
    }

}
