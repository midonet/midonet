/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package com.midokura.midolman.agent.sensor;

import com.midokura.midolman.agent.interfaces.InterfaceDescription;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.junit.Assert.assertThat;

public class TestIpTuntapInterfaceSensor {
    @Test
    public void testUpdateInterfaceData() throws Exception {

        InterfaceSensor interfaceSensor = new IpTuntapInterfaceSensor () {
            @Override
            protected List<String> getTuntapOutput() {
                return Arrays.asList(
                        "xxx: tap vnet_hdr"
                );
            }
        };

        List<InterfaceDescription> interfaces = new ArrayList<InterfaceDescription>();

        // Add one interface to test
        interfaces.add(new InterfaceDescription("xxx"));

        // Do the interface updating
        interfaces = interfaceSensor.updateInterfaceData(interfaces);

        //Check that we parsed all the interfaces
        assertThat(interfaces.size(), equalTo(1));

        // Check the interface
        InterfaceDescription interfaceDescription = interfaces.get(0);
        assertThat(interfaceDescription.getName(), equalTo("xxx"));
        assertThat(interfaceDescription.getEndpoint(), equalTo(InterfaceDescription.Endpoint.TUNTAP));
    }
}
