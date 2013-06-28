// Copyright 2013 Midokura Inc.

package org.midonet.midolman.state;

import org.midonet.midolman.rules.ChainPacketContext;
import org.midonet.sdn.flows.WildcardMatch;


public interface ConditionSet {
    boolean matches(ChainPacketContext fwdInfo, WildcardMatch pktMatch,
                    boolean isPortFilter);
}
