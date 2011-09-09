/* Copyright 2011 Midokura Inc. */

package com.midokura.midolman;

import java.util.UUID;
import java.net.InetAddress;

import org.openflow.protocol.OFMatch;
import org.openflow.protocol.OFFlowRemoved.OFFlowRemovedReason;
import org.openflow.protocol.OFPhysicalPort;

import org.junit.Test;
import static org.junit.Assert.*;

import com.midokura.midolman.AbstractController;
import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnection;
import com.midokura.midolman.state.PortLocationMap;


class AbstractControllerTester extends AbstractController {
    public AbstractControllerTester(
            int datapathId,
            UUID switchUuid,
            int greKey,
            OpenvSwitchDatabaseConnection ovsdb,
            PortLocationMap dict,  /* FIXME(jlm): Replace with PortToIntMap */
            long flowExpireMinMillis,
            long flowExpireMaxMillis,
            long idleFlowExpireMillis,
            InetAddress internalIp) {
        super(datapathId, switchUuid, greKey, ovsdb, dict, flowExpireMinMillis,
	      flowExpireMaxMillis, idleFlowExpireMillis, internalIp);
    }

    @Override 
    public void onPacketIn(int bufferId, int totalLen, short inPort,
		           byte[] data) { }

    @Override
    public void onFlowRemoved(OFMatch match, long cookie,
            short priority, OFFlowRemovedReason reason, int durationSeconds,
            int durationNanoseconds, short idleTimeout, long packetCount,
            long byteCount) { }

    @Override
    public void clear() { }

    @Override
    public void sendFlowModDelete(boolean strict, OFMatch match,
                                  int priority, int outPort) { }

    @Override
    protected void addPort(OFPhysicalPort portDesc, short portNum) { }

    @Override
    protected void deletePort(OFPhysicalPort portDesc) { }

    @Override
    protected void modifyPort(OFPhysicalPort portDesc) { }
}


public class TestAbstractController {

    private AbstractController controller;

    @Test
    public void testConstruction() {
        controller = new AbstractControllerTester(
	                     43 /* datapathId */,
			     UUID.randomUUID() /* switchUuid */,
       			     5 /* greKey */,
 			     null /* ovsdb */,
 			     null /* PortLocationMap dict */,
 			     260 * 1000 /* flowExpireMinMillis */,
 			     320 * 1000 /* flowExpireMaxMillis */,
 			     60 * 1000 /* idleFlowExpireMillis */,
			     null /* internalIp */);
        assertTrue(true);
    }
}
