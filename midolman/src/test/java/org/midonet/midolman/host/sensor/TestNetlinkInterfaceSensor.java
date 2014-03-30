/*
 * Copyright 2012 Midokura Pte. Ltd.
 */
package org.midonet.midolman.host.sensor;

import java.util.Comparator;
import java.util.Set;
import java.util.TreeSet;

import org.junit.Test;

import org.midonet.midolman.host.interfaces.InterfaceDescription;
import org.midonet.odp.DpPort;
import org.midonet.odp.ports.InternalPort;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.junit.Assert.assertThat;

/**
 * Netlink Interface sensor tests
 */
public class TestNetlinkInterfaceSensor {

    NetlinkInterfaceSensor netlinkInterfaceSensor;

    @Test
    public void testUpdateInternalPortInterfaceData() throws Exception {

        Set<InterfaceDescription> interfaces = new TreeSet<>(new Comparator<InterfaceDescription>() {
            @Override
            public int compare(InterfaceDescription o1, InterfaceDescription o2) {
                return 1;
            }
        });

        interfaces.add(new InterfaceDescription("testBridge0"));
        interfaces.add(new InterfaceDescription("testInterface0"));

        netlinkInterfaceSensor = new NetlinkInterfaceSensor() {

            @Override
            protected DpPort getDatapathPort(String portName) {
                return new InternalPort(portName);
            }

        };

        netlinkInterfaceSensor.updateInterfaceData(interfaces);

        assertThat(interfaces.size(), equalTo(2));

        for (InterfaceDescription id : interfaces) {
            assertThat(id.getEndpoint(),
                    equalTo(InterfaceDescription.Endpoint.DATAPATH));
            assertThat(id.getType(),
                    equalTo(InterfaceDescription.Type.VIRT));
            assertThat(id.getPortType(),
                    equalTo(DpPort.Type.Internal));
        }
    }
}
