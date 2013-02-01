/**
 * ServiceFlowController.java - An interface for port service flows.
 *
 * Copyright (c) 2012 Midokura KK. All rights reserved.
 */

package org.midonet.midolman.layer3;

public interface ServiceFlowController {

    public void setServiceFlows(short localPortNum, short remotePortNum,
                                int localAddr, int remoteAddr,
                                short localTport, short remoteTport);
}
