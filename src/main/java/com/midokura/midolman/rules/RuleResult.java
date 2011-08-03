package com.midokura.midolman.rules;

import com.midokura.midolman.openflow.MidoMatch;

public class RuleResult {

    public Action action;
    public String jumpToChain;
    public MidoMatch match;
    public boolean trackConnection;

    public RuleResult(Action action, String jumpToChain, MidoMatch match,
            boolean trackConnection) {
        super();
        this.action = action;
        this.jumpToChain = jumpToChain;
        this.match = match;
        this.trackConnection = trackConnection;
    }
}
