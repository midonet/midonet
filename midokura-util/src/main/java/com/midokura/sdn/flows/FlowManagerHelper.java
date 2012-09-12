/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package com.midokura.sdn.flows;

import com.midokura.netlink.Callback;
import com.midokura.sdn.dp.Flow;
import com.midokura.sdn.dp.FlowMatch;

public interface FlowManagerHelper {

    public Flow getFlow(FlowMatch flowMatch);

    public void removeFlow(Flow flow, Callback<Flow> cb);

    public void removeWildcardFlow(WildcardFlow flow);
}
