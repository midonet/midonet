// Copyright 2011 Midokura Inc.

package com.midokura.midolman;

import java.util.Map;

public interface AbstractControllerMXBean {
    
    int getGreKey();

    String getPeerOfTunnelPortNum(int tunnelPortNum);
    Integer getTunnelPortNumOfPeer(String peerIP);
    
    Map<Integer, String> getTunnelPortToAddressMap();
    
}
