package com.midokura.midolman;

import java.util.Map;

public interface AbstractControllerMXBean {
    
    Map<Integer, Integer> getTunnelPortNumToPeerIp();

}
