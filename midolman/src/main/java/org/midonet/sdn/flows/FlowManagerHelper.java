/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package org.midonet.sdn.flows;

import org.midonet.odp.Flow;
import org.midonet.odp.FlowMatch;


public interface FlowManagerHelper {

    public void getFlow(FlowMatch flowMatch);

    public void removeFlow(Flow flow);

    public void removeWildcardFlow(WildcardFlow flow);
}
