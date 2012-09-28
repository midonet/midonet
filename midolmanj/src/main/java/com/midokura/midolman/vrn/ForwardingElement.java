/*
 * Copyright 2012 Midokura Europe SARL
 */

package com.midokura.midolman.vrn;

import java.util.UUID;
import javax.management.JMException;

import org.apache.zookeeper.KeeperException;
import org.openflow.protocol.OFMatch;

import com.midokura.midolman.MidoMatch;
import com.midokura.packets.Ethernet;
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
    // TODO(pino): This isn't used.  Should we just remove it?  Or should we
    // start using this (but where)?
    public interface xxxPacketContext {
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
