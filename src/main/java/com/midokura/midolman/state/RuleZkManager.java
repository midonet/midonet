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
import com.midokura.midolman.state.ChainZkManager.ChainConfig;

/**
 * This class was created to handle multiple ops feature in Zookeeper.
 * 
 * @version 1.6 11 Sept 2011
 * @author Ryu Ishimoto
 */
public class RuleZkManager extends ZkManager {

    /**
     * Constructor to set ZooKeeper and base path.
     * 
     * @param zk
     *            ZooKeeper object.
     * @param basePath
     *            The root path.
     */
    public RuleZkManager(ZooKeeper zk, String basePath) {
        super(zk, basePath);
    }

    public List<Op> prepareRuleCreate(ZkNodeEntry<UUID, Rule> ruleEntry)
            throws ZkStateSerializationException, KeeperException,
            InterruptedException {
        List<Op> ops = new ArrayList<Op>();
        try {
            ops.add(Op.create(pathManager.getRulePath(ruleEntry.key),
                    serialize(ruleEntry.value), Ids.OPEN_ACL_UNSAFE,
                    CreateMode.PERSISTENT));
        } catch (IOException e) {
            throw new ZkStateSerializationException("Could not serialize Rule",
                    e, Rule.class);
        }
        ops.add(Op.create(pathManager.getChainRulePath(ruleEntry.value.chainId,
                ruleEntry.key), null, Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT));
        return ops;
    }

    public UUID create(Rule rule) throws InterruptedException, KeeperException,
            ZkStateSerializationException {
        UUID id = UUID.randomUUID();
        ZkNodeEntry<UUID, Rule> ruleNode = new ZkNodeEntry<UUID, Rule>(id, rule);
        this.zk.multi(prepareRuleCreate(ruleNode));
        return id;
    }

    public ZkNodeEntry<UUID, Rule> get(UUID id) throws KeeperException,
            InterruptedException, ZkStateSerializationException {
        byte[] data = zk.getData(pathManager.getRulePath(id), null, null);
        Rule rule = null;
        try {
            rule = deserialize(data, Rule.class);
        } catch (IOException e) {
            throw new ZkStateSerializationException(
                    "Could not deserialize chain " + id + " to Rule", e,
                    Rule.class);
        }
        return new ZkNodeEntry<UUID, Rule>(id, rule);
    }

    public List<ZkNodeEntry<UUID, Rule>> list(UUID chainId)
            throws KeeperException, InterruptedException,
            ZkStateSerializationException {
        List<ZkNodeEntry<UUID, Rule>> result = new ArrayList<ZkNodeEntry<UUID, Rule>>();
        List<String> rules = zk.getChildren(pathManager
                .getChainRulesPath(chainId), null);
        for (String rule : rules) {
            // For now, get each one.
            result.add(get(UUID.fromString(rule)));
        }
        return result;
    }

    public List<Op> getDeleteOps(UUID id, UUID chainId) {
        List<Op> ops = new ArrayList<Op>();
        ops.add(Op.delete(pathManager.getChainRulePath(chainId, id), -1));
        ops.add(Op.delete(pathManager.getRulePath(id), -1));
        return ops;
    }

    public void delete(UUID id) throws InterruptedException, KeeperException,
            ClassNotFoundException, ZkStateSerializationException {
        ZkNodeEntry<UUID, Rule> rule = get(id);
        delete(id, rule.value.chainId);
    }

    public void delete(UUID id, UUID chainId) throws InterruptedException,
            KeeperException, ClassNotFoundException {
        this.zk.multi(getDeleteOps(id, chainId));
    }

}
