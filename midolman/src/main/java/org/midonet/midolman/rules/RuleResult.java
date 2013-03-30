/*
 * Copyright 2011 Midokura KK
 */

package org.midonet.midolman.rules;

import java.util.UUID;

import org.midonet.sdn.flows.WildcardMatch;


public class RuleResult {

    public enum Action {
        ACCEPT, CONTINUE, DROP, JUMP, REJECT, RETURN;
    }

    public Action action;
    public UUID jumpToChain;
    public WildcardMatch pmatch;

    public RuleResult(Action action, UUID jumpToChain, WildcardMatch match) {
        this.action = action;
        this.jumpToChain = jumpToChain;
        this.pmatch = match;
    }

    @Override
    public int hashCode() {
        int hash = 1;
        if (null != action)
            hash = hash * 13 + action.hashCode();
        if (null != jumpToChain)
            hash = hash * 11 + jumpToChain.hashCode();
        if (null != pmatch)
            hash = hash * 17 + pmatch.hashCode();
        return hash;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other)
            return true;
        if (!(other instanceof RuleResult))
            return false;
        RuleResult res = (RuleResult) other;
        if (action == null || res.action == null) {
            if (action != res.action)
                return false;
        } else if (!action.equals(res.action)) {
            return false;
        }
        if (jumpToChain == null || res.jumpToChain == null) {
            if (jumpToChain != res.jumpToChain)
                return false;
        } else if (!jumpToChain.equals(res.jumpToChain)) {
            return false;
        }
        if (pmatch == null || res.pmatch == null) {
            if (pmatch != res.pmatch)
                return false;
        } else if (!pmatch.equals(res.pmatch)) {
            return false;
        }
        return true;
    }

    @Override
    public String toString() {
        return "RuleResult [action=" + action + ", jumpToChain=" + jumpToChain +
               ", pmatch=" + pmatch + "]";
    }
}
