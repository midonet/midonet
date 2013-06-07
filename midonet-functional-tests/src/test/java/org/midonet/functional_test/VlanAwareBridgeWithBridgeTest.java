/*
 * Copyright 2011 Midokura Europe SARL
 */

package org.midonet.functional_test;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import akka.util.Duration;
import org.midonet.client.resource.Bridge;
import org.midonet.client.resource.BridgePort;
import org.midonet.client.resource.Host;
import org.midonet.midolman.topology.LocalPortActive;
import static junit.framework.Assert.fail;
import static org.hamcrest.CoreMatchers.hasItems;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

/**
 * This extends the VlanAwareBridgeTest initializing with a topology based
 * purely on Bridge devices, that tests the enhanced version of this device
 * that supports vlan-awareness.
 */
public class VlanAwareBridgeWithBridgeTest extends VlanAwareBridgeTest {

    @Override
    protected boolean hasMacLearning() {
        return false;
    }

    @Override
    public void setup() {
        Host host = thisHost;

        // Create the two virtual bridges and plug the "vm" taps
        Bridge br1 = apiClient.addBridge().tenantId(tenantName).name("br1").create();
        BridgePort br1IntPort = br1.addInteriorPort().create();
        BridgePort br1ExtPort = br1.addExteriorPort().create();
        host.addHostInterfacePort()
            .interfaceName(vm1Tap.getName())
            .portId(br1ExtPort.getId()).create();

        Bridge br2 = apiClient.addBridge().tenantId(tenantName).name("br2").create();
        BridgePort br2IntPort = br2.addInteriorPort().create();
        BridgePort br2ExtPort = br2.addExteriorPort().create();
        host.addHostInterfacePort()
            .interfaceName(vm2Tap.getName())
            .portId(br2ExtPort.getId()).create();

        // Create the vlan-aware bridge
        Bridge vlanBr = apiClient.addBridge()
                                 .tenantId(tenantName)
                                 .name("vlanBr1")
                                 .create();

        // Create ports linking vlan-bridge with each bridge, and assign vlan id
        BridgePort vlanPort1 = vlanBr.addInteriorPort()
                                     .vlanId(vlanId1)
                                     .create();
        vlanPort1.link(br1IntPort.getId());

        BridgePort vlanPort2 = vlanBr.addInteriorPort()
                                     .vlanId(vlanId2)
                                     .create();
        vlanPort2.link(br2IntPort.getId());

        // Create the trunk ports
        BridgePort trunk1 = vlanBr.addExteriorPort().create();
        BridgePort trunk2 = vlanBr.addExteriorPort().create();

        host.addHostInterfacePort()
            .interfaceName(trunkTap1.getName())
            .portId(trunk1.getId())
            .create();

        host.addHostInterfacePort()
            .interfaceName(trunkTap2.getName())
            .portId(trunk2.getId())
            .create();

        Set<UUID> activePorts = new HashSet<UUID>();
        for (int i = 0; i < 4; i++) {
            LocalPortActive activeMsg = probe.expectMsgClass(
                Duration.create(10, TimeUnit.SECONDS),
                LocalPortActive.class);
            log.info("Received one LocalPortActive message from stream.");
            assertTrue("The port should be active.", activeMsg.active());
            activePorts.add(activeMsg.portID());
        }
        assertThat("The 4 ports should be active.", activePorts,
                   hasItems(trunk1.getId(), trunk2.getId(),
                            br1ExtPort.getId(), br2ExtPort.getId()));
        // All set

        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            fail("W00t!! interrupted exception!!");
        }

    }

}
