// Copyright 2012 Midokura Inc.

package com.midokura.midolman;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.openflow.protocol.OFFlowRemoved.OFFlowRemovedReason;
import org.openflow.protocol.OFMatch;

import com.midokura.midolman.ForwardingElement.ForwardInfo;
import com.midokura.midolman.packets.Ethernet;
import com.midokura.midolman.packets.IntIPv4;
import com.midokura.midolman.packets.MAC;
import com.midokura.midolman.state.Directory;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnection;


public class MockVRNController extends AbstractController
        implements VRNControllerIface {

    static class GeneratedPacket {
        Ethernet eth;
        UUID portID;
    };

    List<GeneratedPacket> generatedPackets;

    public void addGeneratedPacket(Ethernet eth, UUID portID) {
        GeneratedPacket newPkt = new GeneratedPacket();
        newPkt.eth = eth;
        newPkt.portID = portID;
        generatedPackets.add(newPkt);
    }

    public MockVRNController(long datapathId, Directory zkDir,
            String zkBasePath, OpenvSwitchDatabaseConnection ovsdb,
            IntIPv4 internalIp, String externalIdKey)
            throws StateAccessException {
        super(datapathId, zkDir, zkBasePath, ovsdb, internalIp, externalIdKey);
        generatedPackets = new ArrayList<GeneratedPacket>();
    }

    public void clear() {}
    public void addVirtualPort(int n, String s, MAC m, UUID u) {}
    public void deleteVirtualPort(int n, UUID u) {}
    public void addServicePort(int n, String s, UUID u) {}
    public void deleteServicePort(int n, String s, UUID u) {}
    public void addTunnelPort(int n, IntIPv4 i) {}
    public void deleteTunnelPort(int n, IntIPv4 i) {}
    protected void portMoved(UUID id, IntIPv4 old, IntIPv4 new_) {}

    public void onFlowRemoved(OFMatch match, long cookie, short priority,
            OFFlowRemovedReason reason, int durationSeconds,
            int durationNanoseconds, short idleTimeout, long packetCount,
            long byteCount, long matchingTunnelId) {}

    public void onFlowRemoved(OFMatch match, long cookie, short priority,
            OFFlowRemovedReason reason, int durationSeconds,
            int durationNanoseconds, short idleTimeout, long packetCount,
            long byteCount) {}

    public void continueProcessing(final ForwardInfo fwdInfo) {}

    public void subscribePortSet(UUID portSetID) {}
    public void unsubscribePortSet(UUID portSetID) {}
    public void addLocalPortToSet(UUID portSetID, UUID portID) {}
    public void removeLocalPortFromSet(UUID portSetID, UUID portID) {}

    public void onPacketIn(int bufferId, int totalLen, short inPort,
                           byte[] data, long tunnelID) {}
}
