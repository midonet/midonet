// Copyright 2013 Midokura Inc.

package org.midonet.midolman.flows;

import java.util.Map;
import java.util.Set;

import org.midonet.sdn.flows.WildcardFlow;
import org.midonet.sdn.flows.WildcardMatch;

public interface WildcardTablesProvider {
    Map<WildcardMatch, WildcardFlow> addTable(Set<WildcardMatch.Field> pattern);

    Map<Set<WildcardMatch.Field>, Map<WildcardMatch, WildcardFlow>> tables();
}
