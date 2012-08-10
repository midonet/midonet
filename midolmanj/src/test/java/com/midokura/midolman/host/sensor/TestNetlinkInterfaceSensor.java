/*
 * Copyright 2012 Midokura Pte. Ltd.
 */
package com.midokura.midolman.host.sensor;

import com.midokura.midolman.host.interfaces.InterfaceDescription;
import com.midokura.sdn.dp.Port;
import com.midokura.sdn.dp.ports.InternalPort;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.junit.Assert.assertThat;

/**
 * Netlink Interface sensor tests
 */
public class TestNetlinkInterfaceSensor {

    NetlinkInterfaceSensor netlinkInterfaceSensor;

    @Test
    public void testUpdateInternalPortInterfaceData() throws Exception {

        List<InterfaceDescription> interfaces =
                new ArrayList<InterfaceDescription>();

        interfaces.add(new InterfaceDescription("testBridge0"));
        interfaces.add(new InterfaceDescription("testInterface0"));

        netlinkInterfaceSensor = new NetlinkInterfaceSensor() {

            @Override
            protected Port getDatapathPort(String portName) {
                return new InternalPort(portName);
            }

        };

        List<InterfaceDescription> updatedInterfaces = netlinkInterfaceSensor
                .updateInterfaceData(interfaces);

        assertThat(updatedInterfaces.size(), equalTo(2));

        // Check the endpoint
        assertThat(updatedInterfaces.get(0).getEndpoint(),
                equalTo(InterfaceDescription.Endpoint.DATAPATH));
        assertThat(updatedInterfaces.get(1).getEndpoint(),
                equalTo(InterfaceDescription.Endpoint.DATAPATH));

        // Check the interface type
        assertThat(updatedInterfaces.get(0).getType(),
                equalTo(InterfaceDescription.Type.VIRT));
        assertThat(updatedInterfaces.get(1).getType(),
                equalTo(InterfaceDescription.Type.VIRT));

        // Check the port type
        assertThat(updatedInterfaces.get(0).getPortType(),
                equalTo(Port.Type.Internal));
        assertThat(updatedInterfaces.get(1).getPortType(),
                equalTo(Port.Type.Internal));

    }
}
