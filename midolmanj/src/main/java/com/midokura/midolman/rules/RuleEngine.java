/*
 * Copyright 2011 Midokura KK
 */

package com.midokura.midolman.rules;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
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

import com.midokura.midolman.layer4.NatMapping;
import com.midokura.midolman.openflow.MidoMatch;
import com.midokura.midolman.rules.RuleResult.Action;
import com.midokura.midolman.state.ChainZkManager;
import com.midokura.midolman.state.Directory;
import com.midokura.midolman.state.RuleZkManager;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midolman.state.ZkNodeEntry;
import com.midokura.midolman.state.ZkStateSerializationException;
import com.midokura.midolman.state.ChainZkManager.ChainConfig;
import com.midokura.midolman.util.Callback;

public class RuleEngine {

    private final static Logger log = LoggerFactory.getLogger(RuleEngine.class);

    private UUID rtrId;
    private String rtrIdStr;
    private RuleZkManager zkRuleMgr;
    private NatMapping natMap;
    private Set<Callback<UUID>> watchers;
    Chains chains;

    public RuleEngine(Directory zkDir, String zkBasePath, UUID rtrId,
                      NatMapping natMap) throws StateAccessException {
        this.rtrId = rtrId;
        rtrIdStr = rtrId.toString();
        this.zkRuleMgr = new RuleZkManager(zkDir, zkBasePath);
        this.natMap = natMap;
        watchers = new HashSet<Callback<UUID>>();
        chains = new Chains(zkDir, zkBasePath, rtrId);
        chains.updateChains();
    }

    public void addWatcher(Callback<UUID> watcher) {
        watchers.add(watcher);
    }

    public void removeWatcher(Callback<UUID> watcher) {
        watchers.remove(watcher);
    }

    private void notifyWatchers() {
        for (Callback<UUID> watcher : watchers)
            // TODO(pino): schedule for later instead of calling them here.
            watcher.call(rtrId);
    }

    /*
    private void updateResources() {
        // Tell the NatMapping about all the current NatTargets for SNAT.
        // TODO(pino): the NatMapping should clean up any old targets that
        // are no longer used and remember the current targets.
        Set<NatTarget> targets = new HashSet<NatTarget>();
        for (List<Rule> chain : ruleChains.values()) {
            for (Rule r : chain) {
                if (r instanceof ForwardNatRule) {
                    ForwardNatRule fR = (ForwardNatRule) r;
                    if (!fR.dnat)
                        targets.addAll(fR.getNatTargets());
                }
            }
        }
        natMap.updateSnatTargets(targets);
    }
    */

    /**
     *
     * @param chainName
     * @param flowMatch
     *            matches the packet that originally entered the datapath. It
     *            will NOT be modified by the rule chain.
     * @param pktMatch
     *            matches the packet that would be seen in this router/chain. It
     *            will NOT be modified by the rule chain.
     * @param inPortId
     * @param outPortId
     * @return
     */
    public RuleResult applyChain(String chainName, MidoMatch flowMatch,
            MidoMatch pktMatch, UUID inPortId, UUID outPortId) {

        return chains.process(chainName, flowMatch, pktMatch, inPortId, outPortId);
    }

    public void freeFlowResources(OFMatch match) {
        log.debug("freeFlowResources: match {}", match);

        natMap.freeFlowResources(match);
    }
}
