/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package org.midonet.sdn.flows;

import org.midonet.odp.Flow;
import org.midonet.odp.FlowMatch;
import org.midonet.util.functors.Callback1;

public interface FlowManagerHelper {

    public void getFlow(FlowMatch flowMatch, Callback1<Flow> getFlowCb);

    public void removeFlow(FlowMatch flowMatch);

    public void removeWildcardFlow(ManagedWildcardFlow flow);
}
