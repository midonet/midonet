/*
 * @(#)ChainDataAccessor        1.6 11/09/05
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.dao;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.midokura.midolman.mgmt.data.ZookeeperService;
import com.midokura.midolman.mgmt.data.dto.Chain;
import com.midokura.midolman.state.ChainZkManager;
import com.midokura.midolman.state.ZkConnection;
import com.midokura.midolman.state.ChainZkManager.ChainConfig;

/**
 * Data access class for chains.
 *
 * @version        1.6 08 Sept 2011
 * @author         Ryu Ishimoto
 */
public class ChainDataAccessor extends DataAccessor {

    /**
     * Constructor
     * @param zkConn Zookeeper connection string
     */
    public ChainDataAccessor(String zkConn) {
        super(zkConn);
    }
    
    private ChainZkManager getChainZkManager() throws Exception {
        ZkConnection conn = ZookeeperService.getConnection(zkConn);        
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
    
    public void create(Chain chain) throws Exception {
        ChainConfig config = convertToConfig(chain);
        ChainZkManager manager = getChainZkManager();
        manager.create(chain.getId(), config);
    }

    public void delete(UUID id) throws Exception {
        ChainZkManager manager = getChainZkManager();
        // TODO: catch NoNodeException if does not exist.
        manager.delete(id);
    }
    
    public void update(UUID id, Chain chain) throws Exception {
        ChainConfig config = convertToConfig(chain);
        ChainZkManager manager = getChainZkManager();
        manager.update(id, config);
    }
    
    public Chain get(UUID id) throws Exception {
        ChainZkManager manager = getChainZkManager();
        ChainConfig config = manager.get(id);
        // TODO: Throw NotFound exception here.
        Chain chain = convertToChain(config);
        chain.setId(id);
        return chain;
    }
    
    public Chain[] list(UUID chainId) throws Exception {
        ChainZkManager manager = getChainZkManager();
        List<Chain> chains = new ArrayList<Chain>();
        HashMap<UUID, ChainConfig> configs = manager.list(chainId);
        for (Map.Entry<UUID, ChainConfig> entry : configs.entrySet()) {
            Chain chain = convertToChain(entry.getValue());
            chain.setId(entry.getKey());
            chains.add(chain);            
        }
        return chains.toArray(new Chain[chains.size()]);
    }   
    
}
