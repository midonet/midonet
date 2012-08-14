/*
 * Copyright 2012 Midokura Europe SARL
 */

package com.midokura.midonet.cluster.client;

public interface BridgeBuilder extends ForwardingElementBuilder {
    void setTunnelKey(long key);
    void setMacLearningTable(MacLearningTable table);
}
