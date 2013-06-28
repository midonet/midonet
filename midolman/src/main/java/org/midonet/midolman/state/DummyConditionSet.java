// Copyright 2013 Midokura Inc.

package org.midonet.midolman.state;

import org.midonet.midolman.rules.ChainPacketContext;
import org.midonet.sdn.flows.WildcardMatch;


public class DummyConditionSet implements ConditionSet {
    private boolean matchReturn;

    public DummyConditionSet(boolean v) {
        matchReturn = v;
    }

    @Override
    public boolean matches(ChainPacketContext fwdInfo,
                           WildcardMatch pktMatch,
                           boolean isPortFilter) {
        return matchReturn;
    }
}
