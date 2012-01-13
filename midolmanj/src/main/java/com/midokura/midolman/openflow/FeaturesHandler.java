/*
 * Copyright 2011 Midokura KK 
 */

package com.midokura.midolman.openflow;

import org.openflow.protocol.OFFeaturesReply;

public interface FeaturesHandler {

    void onFeatures(OFFeaturesReply features);
    
}
