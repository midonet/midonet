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

import com.midokura.midolman.state.PortLocationDictionary;
import com.midokura.midolman.state.MacPortDictionary;

public class BridgeController extends AbstractController {
    
    Logger log = LoggerFactory.getLogger(BridgeController.class);

    MacPortDictionary mac_to_port;

    public BridgeController(int datapathId, UUID switchUuid, int greKey, PortLocationDictionary dict,
            long flowExpireMinMillis, long flowExpireMaxMillis, long idleFlowExpireMillis, InetAddress internalIp) {
        super(datapathId, switchUuid, greKey, dict, flowExpireMinMillis, flowExpireMaxMillis, idleFlowExpireMillis, internalIp);
        // TODO Auto-generated constructor stub
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
