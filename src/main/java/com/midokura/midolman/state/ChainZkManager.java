/*
 * @(#)ChainZkManager        1.6 11/09/08
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.state;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.ZooDefs.Ids;

import com.midokura.midolman.rules.Rule;

/**
 * This class was created to handle multiple ops feature in Zookeeper.
 * 
 * @version        1.6 11 Sept 2011
 * @author         Ryu Ishimoto
 */
public class ChainZkManager extends ZkManager {
    
    public static class ChainConfig implements Serializable {

        private static final long serialVersionUID = 1L;
        public UUID routerId = null;
        public String name = null;

        public ChainConfig() {
        }
        
        public ChainConfig(String name, UUID routerId) {
            this.name = name;
            this.routerId = routerId;
        }
    }
    
    /**
     * Constructor to set ZooKeeper and base path.
     * @param zk  ZooKeeper object.
     * @param basePath  The root path.
     */
    public ChainZkManager(ZooKeeper zk, String basePath) {
    	super(zk, basePath);
    }
    
    /**
     * Create a new chain in ZK.
     * @param id  Chain UUID.
     * @param chain  ChainConfig object to add.
     * @throws IOException Serialization error.
     * @throws KeeperException ZooKeeper error.
     * @throws InterruptedException Thread paused too long.
     */
    public void create(UUID id, ChainConfig chain) 
            throws IOException, InterruptedException, KeeperException {
        List<Op> ops = new ArrayList<Op>();
        ops.add(Op.create(pathManager.getChainPath(id), serialize(chain), 
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
        ops.add(Op.create(pathManager.getChainRulesPath(id), null, 
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
        ops.add(Op.create(pathManager.getRouterChainPath(chain.routerId, id),
                null, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
        this.zk.multi(ops);
    }
    
    public List<Op> getDeleteOps(UUID id, UUID routerId) 
            throws KeeperException, InterruptedException, 
                IOException, ClassNotFoundException {
        List<Op> ops = new ArrayList<Op>();
        RuleZkManager ruleZk = new RuleZkManager(zk, basePath);
        HashMap<UUID, Rule> rules = ruleZk.list(id);
        for (Map.Entry<UUID, Rule> entry : rules.entrySet()) {
            ops.addAll(ruleZk.getDeleteOps(entry.getKey(), id));
        }
        ops.add(Op.delete(pathManager.getRouterChainPath(routerId, id), -1));
        ops.add(Op.delete(pathManager.getChainPath(id), -1));
        return ops;
    }
    
    public void delete(UUID id) 
            throws KeeperException, InterruptedException, 
                IOException, ClassNotFoundException {
        ChainConfig chain = get(id);
        delete(id, chain.routerId);
    }
    
    public void delete(UUID id, UUID routerId) 
            throws InterruptedException, KeeperException, IOException, 
                ClassNotFoundException { 
        this.zk.multi(getDeleteOps(id, routerId));
    }
    
    /**
     * Get the ChainConfig object for the given UUID
     * @param id  Chain ID.
     * @return  ChainConfig object.
     * @throws KeeperException   ZooKeeper error.
     * @throws InterruptedException  Thread paused too long.
     * @throws IOException  Serialization error.
     * @throws ClassNotFoundException   Class not found.
     */
    public ChainConfig get(UUID id) 
            throws KeeperException, InterruptedException,
                IOException, ClassNotFoundException {
        byte[] data = zk.getData(pathManager.getChainPath(id), null, null);
        return deserialize(data, ChainConfig.class);
    }
    
    /**
     * Update a chain data.
     * @param id  Chain UUID
     * @param chain  ChainConfig object.
     * @throws IOException  Serialization error.
     * @throws InterruptedException 
     * @throws KeeperException 
     */
    public void update(UUID id, ChainConfig chain)
            throws IOException, KeeperException, InterruptedException {
        zk.setData(pathManager.getChainPath(id), serialize(chain), -1);
    }
    
    /**
     * Get a list of ChainConfig objects of a router.
     * @param routerId  Router UUID,
     * @return  An array of ChainConfigs
     * @throws KeeperException  Zookeeper exception.
     * @throws InterruptedException  Paused thread interrupted.
     * @throws ClassNotFoundException  Unknown class.
     * @throws IOException  Serialization error.
     */
    public HashMap<UUID, ChainConfig> list(UUID routerId) 
            throws KeeperException, InterruptedException, 
                IOException, ClassNotFoundException {
        HashMap<UUID, ChainConfig> configs = new HashMap<UUID, ChainConfig>();
        List<String> chainIds = zk.getChildren(
                pathManager.getRouterChainsPath(routerId), null);
        for (String chainId : chainIds) {
            // For now get each one.
            UUID id = UUID.fromString(chainId);
            configs.put(id, get(id));
        }
        return configs;
    }    
}
