package com.midokura.midolman;

import java.util.Map;

public interface AbstractControllerMXBean {
    
    int getGreKey();
    Map<Integer, Integer> getTunnelPortNumToPeerIp();

}
