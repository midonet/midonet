/*
 * @(#)ChainDataAccessor        1.6 11/09/05
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.dao;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.midokura.midolman.mgmt.data.ZookeeperService;
import com.midokura.midolman.mgmt.data.dto.Chain;
import com.midokura.midolman.state.ChainZkManager;
import com.midokura.midolman.state.ZkConnection;
import com.midokura.midolman.state.ZkNodeEntry;
import com.midokura.midolman.state.ChainZkManager.ChainConfig;

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
    public ChainDataAccessor(String zkConn, int timeout) {
        super(zkConn, timeout);
    }

    private ChainZkManager getChainZkManager() throws Exception {
        ZkConnection conn = ZookeeperService.getConnection(zkConn, zkTimeout);
        return new ChainZkManager(conn.getZooKeeper(), "/midolman");
    }

    private static ChainConfig convertToConfig(Chain chain) {
        return new ChainConfig(chain.getName(), chain.getRouterId());
    }

    private static Chain convertToChain(ChainConfig config) {
        Chain chain = new Chain();
        chain.setName(config.name);
        chain.setRouterId(config.routerId);
        return chain;
    }

    private static Chain convertToChain(ZkNodeEntry<UUID, ChainConfig> entry) {
        Chain c = convertToChain(entry.value);
        c.setId(entry.key);
        return c;
    }

    public UUID create(Chain chain) throws Exception {
        return getChainZkManager().create(convertToConfig(chain));
    }

    public Chain get(UUID id) throws Exception {
        // TODO: Throw NotFound exception here.
        return convertToChain(getChainZkManager().get(id));
    }

    public Chain[] list(UUID routerId) throws Exception {
        ChainZkManager manager = getChainZkManager();
        List<Chain> chains = new ArrayList<Chain>();
        List<ZkNodeEntry<UUID, ChainConfig>> entries = manager.list(routerId);
        for (ZkNodeEntry<UUID, ChainConfig> entry : entries) {
            chains.add(convertToChain(entry));
        }
        return chains.toArray(new Chain[chains.size()]);
    }
   
    public void delete(UUID id) throws Exception {
        ChainZkManager manager = getChainZkManager();
        // TODO: catch NoNodeException if does not exist.
        manager.delete(id);
    }

}
