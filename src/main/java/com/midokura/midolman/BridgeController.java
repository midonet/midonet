/*
 * Copyright 2011 Midokura KK 
 */

package com.midokura.midolman;

import java.net.InetAddress;
import java.util.UUID;

import org.openflow.protocol.OFFlowRemoved.OFFlowRemovedReason;
import org.openflow.protocol.OFMatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.state.PortLocationMap;
import com.midokura.midolman.state.MacPortMap;

public class BridgeController extends AbstractController {
    
    Logger log = LoggerFactory.getLogger(BridgeController.class);

    PortLocationMap port_locs;
    MacPortMap mac_to_port;
    long mac_port_timeout;

    public BridgeController(int datapathId, UUID switchUuid, int greKey,
            PortLocationMap port_loc_map, MacPortMap mac_port_map,
            long flowExpireMinMillis, long flowExpireMaxMillis, 
            long idleFlowExpireMillis, InetAddress publicIp, 
            long macPortTimeoutMillis) {
        super(datapathId, switchUuid, greKey, dict, flowExpireMinMillis,
              flowExpireMaxMillis, idleFlowExpireMillis, publicIp);
        mac_to_port = mac_port_map;
        mac_port_timeout = macPortTimeoutMillis;
        port_locs = port_loc_map;
    }

    @Override
    public void onFlowRemoved(OFMatch match, long cookie, short priority, OFFlowRemovedReason reason,
            int durationSeconds, int durationNanoseconds, short idleTimeout, long packetCount, long byteCount) {
        // TODO Auto-generated method stub
        
    }

    @Override
    public void onPacketIn(int bufferId, int totalLen, short inPort, byte[] data) {
        // TODO Auto-generated method stub
        
    }

}
