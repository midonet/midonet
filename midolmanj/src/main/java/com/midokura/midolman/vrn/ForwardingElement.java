/*
 * Copyright 2012 Midokura Europe SARL
 */

package com.midokura.midolman.vrn;

import java.util.UUID;
import javax.management.JMException;

import org.apache.zookeeper.KeeperException;
import org.openflow.protocol.OFMatch;

import com.midokura.midolman.openflow.MidoMatch;
import com.midokura.midolman.packets.Ethernet;
import com.midokura.midolman.packets.IPv4;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midolman.state.ZkStateSerializationException;


public interface ForwardingElement {

    void process(ForwardInfo fwdInfo)
         throws StateAccessException, KeeperException;
    void addPort(UUID portId)
         throws ZkStateSerializationException, StateAccessException,
                KeeperException, InterruptedException, JMException;
    void removePort(UUID portId) throws StateAccessException;
    UUID getId();
    void freeFlowResources(OFMatch match, UUID inPortId);
    void destroy();

    public enum Action {
        DROP,
        NOT_IPV4,
        FORWARD,
        CONSUMED,
        PAUSED,
    }

    // For use by Bridge and Router ForwardingElements.
    public interface PacketContext {
        boolean isGeneratedPacket();
        UUID getInPortId();
        Ethernet getPktIn();
        MidoMatch getMatchIn();   // The return value should not be modified.
        MidoMatch getMatchOut();  // The return value may be modified.
        void addRemovalNotification(UUID deviceId);
        void setAction(Action action);
        void setOutPortId(UUID outPortId);
    }

}
