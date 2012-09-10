/*
 * Copyright 2012 Midokura Inc.
 */

package com.midokura.midolman.rules;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.openflow.protocol.OFMatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.cache.Cache;
import com.midokura.cache.CacheWithPrefix;
import com.midokura.midolman.layer4.NatLeaseManager;
import com.midokura.midolman.layer4.NatMapping;
import com.midokura.midolman.state.Directory;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midolman.state.zkManagers.FiltersZkManager;
import com.midokura.midolman.vrn.VRNControllerIface;
import com.midokura.util.eventloop.Reactor;


public class ChainProcessor implements ChainGetter {

    private final static Logger log =
        LoggerFactory.getLogger(ChainProcessor.class);

    private Directory zkDir;
    private String zkBasePath;
    private Cache cache;
    private Reactor reactor;
    protected Map<UUID, Chain> chainByUuid = new HashMap<UUID, Chain>();
    private Map<UUID, NatMapping> natMappingMap =
            new HashMap<UUID, NatMapping>();
    private VRNControllerIface ctrl;

    public ChainProcessor(Directory dir, String zkBasePath, Cache cache,
                          Reactor reactor, VRNControllerIface ctrl)
            throws StateAccessException {
        this.zkDir = dir;
        this.zkBasePath = zkBasePath;
        this.cache = cache;
        this.reactor = reactor;
        this.ctrl = ctrl;
    }

    public NatMapping getNatMapping(UUID ownerId) {
        if (natMappingMap.containsKey(ownerId)) {
            return natMappingMap.get(ownerId);
        } else {
            NatMapping natMapping = new NatLeaseManager(
                    new FiltersZkManager(zkDir, zkBasePath),
                    ownerId,
                    new CacheWithPrefix(cache, ownerId.toString()), reactor);
            natMappingMap.put(ownerId, natMapping);
            return natMapping;
        }
    }

    public void freeFlowResources(OFMatch match, UUID ownerId) {
        log.debug("freeFlowResources: match {}", match);
        // If the NatMapping doesn't exist, it doesn't have resources to free.
        NatMapping natMap = natMappingMap.get(ownerId);
        if (null != natMap)
            natMap.freeFlowResources(match);
    }

    private void rememberChain(Chain chain, UUID id) {
        chainByUuid.put(id, chain);
    }

    private void forgetChain(UUID id) {
        chainByUuid.remove(id);
    }

    // Chains are not pre-loaded.  Retrieve them the first time they
    // are requested
    public Chain getOrCreateChain(UUID id) throws StateAccessException {
        if (null == id)
            return null;
        Chain chain = chainByUuid.get(id);
        if (null != chain)
            return chain;
        chain = new Chain(id, zkDir, zkBasePath, ctrl);
        rememberChain(chain, id);
        return chain;
    }
}
