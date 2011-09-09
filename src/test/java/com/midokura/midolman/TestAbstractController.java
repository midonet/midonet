/* Copyright 2011 Midokura Inc. */

package com.midokura.midolman;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.net.InetAddress;

import org.openflow.protocol.OFMatch;
import org.openflow.protocol.OFPortStatus.OFPortReason;
import org.openflow.protocol.OFFlowRemoved.OFFlowRemovedReason;
import org.openflow.protocol.OFPhysicalPort;

import org.junit.Test;
import static org.junit.Assert.*;

import com.midokura.midolman.AbstractController;
import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnection;
import com.midokura.midolman.openvswitch.MockOpenvSwitchDatabaseConnection;
import com.midokura.midolman.state.PortLocationMap;


class AbstractControllerTester extends AbstractController {
    public List<OFPhysicalPort> portsAdded;
    public List<OFPhysicalPort> portsRemoved;
    public List<OFPhysicalPort> portsModified;
 
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
        portsAdded = new ArrayList<OFPhysicalPort>();
        portsRemoved = new ArrayList<OFPhysicalPort>();
        portsModified = new ArrayList<OFPhysicalPort>();
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
    protected void addPort(OFPhysicalPort portDesc, short portNum) { 
        assertEquals(portDesc.getPortNumber(), portNum);
        portsAdded.add(portDesc);
    }

    @Override
    protected void deletePort(OFPhysicalPort portDesc) { 
        portsRemoved.add(portDesc);
    }

    @Override
    protected void modifyPort(OFPhysicalPort portDesc) {
        portsModified.add(portDesc);
    }
}


public class TestAbstractController {

    private AbstractControllerTester controller;

    @Test
    public void testController() {
        controller = new AbstractControllerTester(
	                     43 /* datapathId */,
			     UUID.randomUUID() /* switchUuid */,
       			     5 /* greKey */,
 			     new MockOpenvSwitchDatabaseConnection() /* ovsdb */,
 			     null /* PortLocationMap dict */,
 			     260 * 1000 /* flowExpireMinMillis */,
 			     320 * 1000 /* flowExpireMaxMillis */,
 			     60 * 1000 /* idleFlowExpireMillis */,
			     null /* internalIp */);

        OFPhysicalPort port1 = new OFPhysicalPort();
        port1.setPortNumber((short) 37);
        port1.setHardwareAddress(new byte[] { 10, 12, 13, 14, 15, 37 });

        assertArrayEquals(new OFPhysicalPort[] { },
			  controller.portsAdded.toArray());
        controller.onPortStatus(port1, OFPortReason.OFPPR_ADD);
        assertArrayEquals(new OFPhysicalPort[] { port1 },
			  controller.portsAdded.toArray());

        assertArrayEquals(new OFPhysicalPort[] { },
			  controller.portsModified.toArray());
        controller.onPortStatus(port1, OFPortReason.OFPPR_MODIFY);
        assertArrayEquals(new OFPhysicalPort[] { port1 },
			  controller.portsModified.toArray());

        assertArrayEquals(new OFPhysicalPort[] { },
			  controller.portsRemoved.toArray());
        controller.onPortStatus(port1, OFPortReason.OFPPR_DELETE);
        assertArrayEquals(new OFPhysicalPort[] { port1 },
			  controller.portsRemoved.toArray());
    }
}
