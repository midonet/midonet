/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package com.midokura.sdn.flows;

import com.midokura.odp.Flow;
import com.midokura.odp.FlowMatch;


public interface FlowManagerHelper {

    public void getFlow(FlowMatch flowMatch);

    public void removeFlow(Flow flow);

    public void removeWildcardFlow(WildcardFlow flow);
}
