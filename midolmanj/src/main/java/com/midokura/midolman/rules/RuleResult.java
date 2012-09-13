/*
 * Copyright 2011 Midokura KK
 */

package com.midokura.midolman.rules;

import java.util.UUID;

import com.midokura.sdn.flows.PacketMatch;


public class RuleResult {

    public enum Action {
        ACCEPT, CONTINUE, DROP, JUMP, REJECT, RETURN;
    }

    public Action action;
    public UUID jumpToChain;
    public PacketMatch pmatch;
    public boolean trackConnection;

    public RuleResult(Action action, UUID jumpToChain, PacketMatch match,
            boolean trackConnection) {
        this.action = action;
        this.jumpToChain = jumpToChain;
        this.pmatch = match;
        this.trackConnection = trackConnection;
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
        int bHash = trackConnection ? 1231 : 1237;
        return hash * 19 + bHash;
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
        return trackConnection == res.trackConnection;
    }

    @Override
    public String toString() {
        return "RuleResult [action=" + action + ", jumpToChain=" + jumpToChain +
               ", pmatch=" + pmatch + ", trackConnection=" + trackConnection +
               "]";
    }
}
