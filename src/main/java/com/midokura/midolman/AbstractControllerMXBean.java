// Copyright 2011 Midokura Inc.

package com.midokura.midolman;

import java.lang.Integer;

public interface AbstractControllerMXBean {
    
    int getGreKey();
    Integer peerOfTunnelPortNum(int tunnelPortNum);
    Integer tunnelPortNumOfPeer(Integer peerIP);
}
