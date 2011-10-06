// Copyright 2011 Midokura Inc.

package com.midokura.midolman;

import java.lang.Integer;

import com.midokura.midolman.packets.IntIPv4;

public interface AbstractControllerMXBean {
    
    int getGreKey();
    IntIPv4 peerOfTunnelPortNum(int tunnelPortNum);
    Integer tunnelPortNumOfPeer(IntIPv4 peerIP);
}
