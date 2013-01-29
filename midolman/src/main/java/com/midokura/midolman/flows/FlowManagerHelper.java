/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package com.midokura.midolman.flows;

import com.midokura.sdn.dp.Flow;
import com.midokura.sdn.dp.FlowMatch;

public interface FlowManagerHelper {

    public void getFlow(FlowMatch flowMatch);

    public void removeFlow(Flow flow);

    public void removeWildcardFlow(WildcardFlow flow);
}
