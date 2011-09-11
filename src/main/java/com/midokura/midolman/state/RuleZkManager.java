/*
 * @(#)RuleZkManager        1.6 11/09/08
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.state;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
public class RuleZkManager extends ZkManager {
    
    /**
     * Constructor to set ZooKeeper and base path.
     * @param zk  ZooKeeper object.
     * @param basePath  The root path.
     */
    public RuleZkManager(ZooKeeper zk, String basePath) {
        super(zk, basePath);
    }
    
    public void create(UUID id, Rule rule) 
            throws IOException, InterruptedException, KeeperException {
        List<Op> ops = new ArrayList<Op>();
        ops.add(Op.create(pathManager.getRulePath(id), serialize(rule), 
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
        ops.add(Op.create(pathManager.getChainRulePath(rule.chainId, id),
                null, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
        this.zk.multi(ops);
    }
    
    public List<Op> getDeleteOps(UUID id, UUID chainId) {
        List<Op> ops = new ArrayList<Op>();
        ops.add(Op.delete(pathManager.getChainRulePath(chainId, id), -1));
        ops.add(Op.delete(pathManager.getRulePath(id), -1));
        return ops;
    }
    
    public void delete(UUID id) 
            throws InterruptedException, KeeperException, 
                IOException, ClassNotFoundException {
        Rule rule = get(id);
        delete(id, rule.chainId);
    }
    
    public void delete(UUID id, UUID chainId)
            throws InterruptedException, KeeperException, 
                IOException, ClassNotFoundException {
        this.zk.multi(getDeleteOps(id, chainId));
    }
    
    public Rule get(UUID id) 
            throws KeeperException, InterruptedException,
                IOException, ClassNotFoundException {
        byte[] data = zk.getData(pathManager.getRulePath(id), null, null);
        return deserialize(data, Rule.class);
    }
    
    public void update(UUID id, Rule rule)
            throws IOException, KeeperException, InterruptedException {
        zk.setData(pathManager.getRulePath(id), serialize(rule), -1);
    }
    
    public HashMap<UUID, Rule> list(UUID chainId) 
            throws KeeperException, InterruptedException, 
                IOException, ClassNotFoundException {
        HashMap<UUID, Rule> rules = new HashMap<UUID, Rule>();
        List<String> ruleIds = zk.getChildren(
                pathManager.getChainRulesPath(chainId), null);
        for (String ruleId : ruleIds) {
            // For now get each one.
            UUID id = UUID.fromString(ruleId);
            rules.put(id, get(id));
        }
        return rules;
    } 
}
