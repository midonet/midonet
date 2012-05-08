/*
 * Copyright 2012 Midokura Inc.
 */

package com.midokura.midolman.rules;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Stack;
import java.util.UUID;

import org.openflow.protocol.OFMatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.eventloop.Reactor;
import com.midokura.midolman.layer4.NatLeaseManager;
import com.midokura.midolman.layer4.NatMapping;
import com.midokura.midolman.openflow.MidoMatch;
import com.midokura.midolman.state.ChainZkManager;
import com.midokura.midolman.state.Directory;
import com.midokura.midolman.state.FiltersZkManager;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midolman.util.Cache;
import com.midokura.midolman.util.CacheWithPrefix;

public class ChainProcessor {

    private final static Logger log =
        LoggerFactory.getLogger(ChainProcessor.class);

    private Map<UUID, Chain> chainByUuid;
    private Directory zkDir;
    private String zkBasePath;
    private Cache cache;
    private Reactor reactor;
    private Map<UUID, NatMapping> natMappingMap;

    public ChainProcessor(Directory dir, String zkBasePath,
                           Cache cache, Reactor reactor)
            throws StateAccessException {
        this.zkDir = dir;
        this.zkBasePath = zkBasePath;
        this.cache = cache;
        this.reactor = reactor;
        chainByUuid = new HashMap<UUID, Chain>();
        natMappingMap = new HashMap<UUID, NatMapping>();
    }

    private NatMapping getNatMapping(UUID ownerId) {
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
        NatMapping natMap = getNatMapping(ownerId);
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
        chain = new Chain(id, zkDir, zkBasePath);
        rememberChain(chain, id);
        return chain;
    }

    /**
     * @param chainID
     * @param flowMatch
     *            matches the packet that originally entered the datapath. It
     *            will NOT be modified by the rule chain.
     * @param pktMatch
     *            matches the packet that would be seen in this router/chain. It
     *            will NOT be modified by the rule chain.
     * @param inPortId
     * @param outPortId
     * @param ownerId
     *            UUID of the element using chainId.
     * @return
     */
    public RuleResult applyChain(UUID chainID, MidoMatch flowMatch,
            MidoMatch pktMatch, UUID inPortId, UUID outPortId, UUID ownerId)
                throws StateAccessException {
        if (null == chainID) {
             return new RuleResult(RuleResult.Action.ACCEPT, null, pktMatch,
                                   false);
        }

        Chain currentChain = getOrCreateChain(chainID);
        Stack<ChainPosition> chainStack = new Stack<ChainPosition>();
        chainStack.push(new ChainPosition(chainID, currentChain.getRules(), 0));
        Set<UUID> traversedChains = new HashSet<UUID>();
        traversedChains.add(chainID);

        RuleResult res = new RuleResult(RuleResult.Action.CONTINUE, null,
                pktMatch.clone(), false);
        while (!chainStack.empty()) {
            ChainPosition cp = chainStack.pop();
            while (cp.position < cp.rules.size()) {
                // Reset the default action and jumpToChain. Keep the
                // transformed match and trackConnection.
                res.action = RuleResult.Action.CONTINUE;
                res.jumpToChain = null;
                cp.rules.get(cp.position).process(flowMatch, inPortId,
                        outPortId, res, getNatMapping(ownerId));
                cp.position++;
                if (res.action.equals(RuleResult.Action.ACCEPT)
                        || res.action.equals(RuleResult.Action.DROP)
                        || res.action.equals(RuleResult.Action.REJECT)) {
                    return res;
                } else if (res.action.equals(RuleResult.Action.JUMP)) {
                    Chain nextChain = getOrCreateChain(res.jumpToChain);
                    if (null == nextChain) {
                        // Let's just ignore jumps to non-existent chains.
                        log.warn("ignoring jump to chain {} -- not found.",
                                 res.jumpToChain);
                        continue;
                    }

                    UUID nextID = nextChain.getID();
                    if (traversedChains.contains(nextID)) {
                        // Avoid jumping to chains we've already seen.
                        log.warn("applyChain {} cannot jump from chain {} to " +
                                 "chain {} -- already visited", new Object[] {
                                 chainID, cp.id, res.jumpToChain });
                        continue;
                    }

                    traversedChains.add(nextID);
                    // Remember the calling chain.
                    chainStack.push(cp);
                    chainStack.push(
                        new ChainPosition(nextID, nextChain.getRules(), 0));
                    break;
                } else if (res.action.equals(RuleResult.Action.RETURN)) {
                    // Stop processing this chain; return to the calling chain.
                    break;
                } else if (res.action.equals(RuleResult.Action.CONTINUE)) {
                    // Move on to the next rule in the same chain.
                    continue;
                } else {
                    log.error("Unknown action type {} in rule chain {}",
                              res.action, cp.id);
                    // TODO: Should we throw an exception?
                    continue;
                }
            }
        }
        // If we fall off the end of the starting chain, we ACCEPT.
        res.action = RuleResult.Action.ACCEPT;
        return res;
    }

    private class ChainPosition {
        UUID id;
        List<Rule> rules;
        int position;

        public ChainPosition(UUID id, List<Rule> rules, int position) {
            this.id = id;
            this.rules = rules;
            this.position = position;
        }
    }

}
