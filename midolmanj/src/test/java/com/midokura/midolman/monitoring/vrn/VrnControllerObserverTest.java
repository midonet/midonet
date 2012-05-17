/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman.monitoring.vrn;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.openflow.protocol.OFPhysicalPort;
import org.openflow.protocol.OFPortStatus;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;

import com.midokura.midolman.AbstractVrnControllerTest;
import com.midokura.midolman.VRNControllerObserver;
import com.midokura.midolman.packets.MAC;
import static com.midokura.midolman.state.PortDirectory.MaterializedRouterPortConfig;

/**
 * Test case for VrnControllerObserver functionality.
 *
 * @author Mihai Claudiu Toader <mtoader@midokura.com>
 *         Date: 5/2/12
 */
public class VrnControllerObserverTest extends AbstractVrnControllerTest {

    OFPhysicalPort port1;
    OFPhysicalPort port2;

    @Override
    @BeforeMethod
    public void setUp() throws Exception {
        super.setUp();

        UUID routerId = createNewRouter();

        int portNumber = 36;

        MaterializedRouterPortConfig portConfig =
            newMaterializedPort(routerId, portNumber,
                                new byte[]{10, 12, 13, 14, 15, 37});

        port1 = toOFPhysicalPort(portNumber, "port1", portConfig);

        portNumber = 37;
        portConfig = newMaterializedPort(routerId, portNumber,
                                         new byte[]{10, 12, 13, 14, 15, 38});

        port2 = toOFPhysicalPort(portNumber, "port2", portConfig);
    }

    private OFPhysicalPort toOFPhysicalPort(int portNum, String portName,
                                            MaterializedRouterPortConfig portConfig) {

        OFPhysicalPort physicalPort = new OFPhysicalPort();

        physicalPort.setHardwareAddress(portConfig.getHwAddr().getAddress());
        physicalPort.setPortNumber((byte) portNum);
        physicalPort.setName(portName);

        return physicalPort;
    }

    @Override
    @AfterMethod
    public void tearDown() throws Exception {
        super.tearDown();
    }

    @Test
    public void testAddRemoveVirtualPortNotifications() throws Exception {

        final Set<UUID> uuids = new HashSet<UUID>();

        VRNControllerObserver observer = new VRNControllerObserver() {
            @Override
            public void addVirtualPort(int portNum, String name, MAC addr,
                                       UUID portId) {
                uuids.add(portId);
            }

            @Override
            public void delVirtualPort(int portNum, UUID portId) {
                uuids.remove(portId);
            }
        };

        getVrnController().addControllerObserver(observer);

        getVrnController().onPortStatus(port1,
                                        OFPortStatus.OFPortReason.OFPPR_ADD);

        assertThat(uuids, hasSize(1));
        assertThat(uuids, containsInAnyOrder(getPortExternalId(port1)));

        getVrnController().onPortStatus(port2,
                                        OFPortStatus.OFPortReason.OFPPR_ADD);

        assertThat(uuids, hasSize(2));
        assertThat(uuids, containsInAnyOrder(
            getPortExternalId(port1),
            getPortExternalId(port2)
        ));

        getVrnController().onPortStatus(port1,
                                        OFPortStatus.OFPortReason.OFPPR_DELETE);

        assertThat(uuids, hasSize(1));
        assertThat(uuids, containsInAnyOrder(getPortExternalId(port2)));

        getVrnController().onPortStatus(port1,
                                        OFPortStatus.OFPortReason.OFPPR_ADD);

        assertThat(uuids, hasSize(2));
        assertThat(uuids, containsInAnyOrder(
            getPortExternalId(port1),
            getPortExternalId(port2)
        ));

        getVrnController().onPortStatus(port1,
                                        OFPortStatus.OFPortReason.OFPPR_DELETE);
        getVrnController().onPortStatus(port2,
                                        OFPortStatus.OFPortReason.OFPPR_DELETE);
        assertThat(uuids, hasSize(0));

        getVrnController().removeControllerObserver(observer);
    }
}
