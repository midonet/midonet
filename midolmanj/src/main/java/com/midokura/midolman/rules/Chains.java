/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package com.midokura.midolman.rules;

import com.midokura.midolman.openflow.MidoMatch;
import com.midokura.midolman.state.ChainZkManager;
import com.midokura.midolman.state.Directory;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midolman.state.ZkNodeEntry;
import com.midokura.midolman.state.ZkStateSerializationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.UUID;

/**
 * Contains all the rule chains for all tenants
 */
public class Chains {
    private final static Logger log = LoggerFactory.getLogger(Chains.class);

    private class ChainWatcher implements Runnable {
        @Override
        public void run() {
            try {
                updateChains();
            } catch (Exception e) {
                log.warn("ChainWatcher.run", e);
            }
        }
    }

    private Map<UUID, Chain> chains;
    private Map<String, UUID> chainNameToUUID;
    private ChainZkManager zkChainMgr;
    private UUID ownerId;
    private ChainWatcher chainWatcher;
    Directory zkDir;
    String zkBasePath;

    public Chains(Directory zkDir, String zkBasePath, UUID ownerId) {
        this.chains = new HashMap<UUID, Chain>();
        this.chainNameToUUID = new HashMap<String, UUID>();
        this.zkChainMgr = new ChainZkManager(zkDir, zkBasePath);
        this.ownerId = ownerId;
        this.chainWatcher = new ChainWatcher();
        this.zkDir = zkDir;
        this.zkBasePath = zkBasePath;
    }

    /**
     * Called when a change in the state (ZooKeeper) has been notified
     * @throws StateAccessException
     * @throws ZkStateSerializationException
     */
    public void updateChains() throws StateAccessException,
            ZkStateSerializationException {
        Collection<ZkNodeEntry<UUID, ChainZkManager.ChainConfig>> entryList = zkChainMgr.list(
                ownerId, chainWatcher);

        HashSet<UUID> updatedChains = new HashSet<UUID>();
        boolean hasUpdates = false;

        // Add new entries, store traversed entries for helping later removal
        for (ZkNodeEntry<UUID, ChainZkManager.ChainConfig> entry : entryList) {
            updatedChains.add(entry.key);
            if (!chains.containsKey(entry.key)) {
                addChain(entry.key, entry.value.name);
                hasUpdates = true;
            }
        }

        // Remove old entries
        for (UUID oldChain : chains.keySet()) {
            if (!updatedChains.contains(oldChain)) {
                removeChain(oldChain);
                hasUpdates = true;
            }
        }

        if (hasUpdates) {
            //updateResources();
            //notifyWatchers();
        }
    }

    private void addChain (UUID chainId, String chainName) {
        Chain chain = new Chain(chainId, chainName, zkDir, zkBasePath, this);
        chains.put(chainId, chain);
        chainNameToUUID.put(chainName, chainId);
        //chain.updateRules();
        log.debug("Added chain {} with Id {}", chainName, chainId);
    }

    private void removeChain (UUID chainId) {
        Chain chain = chains.get(chainId);
        chains.remove(chainId);
        chainNameToUUID.remove(chain.getChainName());
        log.debug("Removed chain {} with id {}", chain.getChainName(), chainId);
    }

    public Chain getChainByName(String chainName) {
        Chain chain = null;

        if (chainNameToUUID.containsKey(chainName)) {
            UUID chainId = chainNameToUUID.get(chainName);
            if (chains.containsKey(chainId)) {
                chain = chains.get(chainId);
            }
        }

        return chain;
    }

    public RuleResult process(String chainName, MidoMatch flowMatch,
                              MidoMatch pktMatch, UUID inPortId, UUID outPortId) {
        Chain chain = getChainByName(chainName);
        if (null == chain) {
            log.debug("Chains.process {} - null, return ACCEPT", chainName);
            return new RuleResult(RuleResult.Action.ACCEPT, null, pktMatch, false);
        } else {
            return chain.process(flowMatch, pktMatch, inPortId, outPortId);
        }
    }
}
