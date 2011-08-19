package com.midokura.midolman;

import java.net.InetAddress;
import java.util.UUID;

import org.openflow.protocol.OFFlowRemoved.OFFlowRemovedReason;
import org.openflow.protocol.OFMatch;
import org.openflow.protocol.OFPhysicalPort;
import org.openflow.protocol.OFPortStatus.OFPortReason;

import com.midokura.midolman.state.PortLocationMap;

public class NetworkController extends AbstractController {

    public NetworkController(int datapathId, UUID switchUuid, int greKey,
            PortLocationMap dict, long flowExpireMinMillis,
            long flowExpireMaxMillis, long idleFlowExpireMillis,
            InetAddress internalIp) {
        super(datapathId, switchUuid, greKey, dict, flowExpireMinMillis,
                flowExpireMaxMillis, idleFlowExpireMillis, internalIp);
        // TODO Auto-generated constructor stub
    }

    @Override
    public void onPacketIn(int bufferId, int totalLen, short inPort, byte[] data) {
        // TODO Auto-generated method stub
        
    }

    @Override
    public void onFlowRemoved(OFMatch match, long cookie, short priority,
            OFFlowRemovedReason reason, int durationSeconds,
            int durationNanoseconds, short idleTimeout, long packetCount,
            long byteCount) {
        // TODO Auto-generated method stub
        
    }

    @Override
    public void onPortStatus(OFPhysicalPort port, OFPortReason status) {
        // TODO Auto-generated method stub
        
    }

    @Override
    public void clear() {
        // TODO Auto-generated method stub
        
    }

    @Override
    public void sendFlowModDelete(boolean strict, OFMatch match, int priority,
            int outPort) {
        // TODO Auto-generated method stub
        
    }

}
