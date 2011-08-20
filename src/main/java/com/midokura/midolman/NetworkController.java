package com.midokura.midolman;

import java.io.IOException;
import java.net.InetAddress;
import java.util.UUID;

import org.apache.zookeeper.KeeperException;
import org.openflow.protocol.OFFlowRemoved.OFFlowRemovedReason;
import org.openflow.protocol.OFMatch;
import org.openflow.protocol.OFPhysicalPort;
import org.openflow.protocol.OFPortStatus.OFPortReason;

import com.midokura.midolman.layer3.Network;
import com.midokura.midolman.state.PortDirectory;
import com.midokura.midolman.state.PortLocationMap;

public class NetworkController extends AbstractController {

    private PortDirectory portDir;
    private Network network;

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
        // Get the Midolman UUID from OVSDB.
        UUID portId = null;
        // Now get the port configuration from ZooKeeper.
        L3DevicePort devPort = null;
        try {
            devPort = new L3DevicePort(portDir, portId);
        } catch (Exception e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        try {
            network.addPort(devPort);
        } catch (KeeperException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        } catch (InterruptedException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        } catch (ClassNotFoundException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
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
