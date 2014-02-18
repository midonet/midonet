/*
 * Copyright 2011 Midokura KK
 */

package org.midonet.midolman.rules;

import java.util.Objects;
import java.util.UUID;

import org.midonet.sdn.flows.WildcardMatch;


public class RuleResult {

    public enum Action {
        ACCEPT(true),
        CONTINUE(false),
        DROP(true),
        JUMP(false),
        REJECT(true),
        RETURN(false);

        private final boolean decisive;

        private Action(boolean decisive) { this.decisive = decisive; }

        /**
         * A decisive action, such as ACCEPT or REJECT, is one which
         * determines the result of a chain application and thus allows
         * us to skip processing of any further rules.
         */
        public boolean isDecisive() { return decisive; }
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
        return Objects.hash(action, jumpToChain, pmatch);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof RuleResult)) return false;

        RuleResult res = (RuleResult)other;
        if (action != res.action) return false;
        if (!Objects.equals(jumpToChain, res.jumpToChain)) return false;
        if (!Objects.equals(pmatch, res.pmatch)) return false;
        return true;
    }

    @Override
    public String toString() {
        return "RuleResult [action=" + action + ", jumpToChain=" + jumpToChain +
               ", pmatch=" + pmatch + "]";
    }
}
