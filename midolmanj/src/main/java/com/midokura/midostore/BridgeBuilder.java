/*
 * Copyright 2012 Midokura Europe SARL
 */

package com.midokura.midostore;

import java.util.UUID;

public interface BridgeBuilder extends ForwardingElementBuilder {
    void setTunnelKey(long key);
    void setMacLearningTable(MacLearningTable table);
}
