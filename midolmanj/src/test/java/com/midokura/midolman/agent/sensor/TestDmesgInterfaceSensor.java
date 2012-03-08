/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package com.midokura.midolman.agent.sensor;

import com.midokura.midolman.agent.interfaces.InterfaceDescription;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.junit.Assert.assertThat;

public class TestDmesgInterfaceSensor {
    @Test
    public void testUpdateInterfaceData() throws Exception {
        
        InterfaceSensor interfaceSensor = new DmesgInterfaceSensor() {
            @Override
            protected List<String> getDmesgOutput(String interfaceName) {
                if (interfaceName.startsWith("eth")) {
                    return Arrays.asList(
                            "[    5.153482] e1000e 0000:00:19.0: eth0: MAC: 7, PHY: 8, PBA No: FFFFFF-0FF"
                    );
                }
                return Collections.emptyList();
            }
        };

        List<InterfaceDescription> interfaces =  new ArrayList<InterfaceDescription>();
        // Add one interface to test
        interfaces.add(new InterfaceDescription("eth0"));

        // Do the interface update
        interfaces = interfaceSensor.updateInterfaceData(interfaces);

        //Check that we parsed all the interfaces
        assertThat(interfaces.size(), equalTo(1));

        // Check the interface
        InterfaceDescription interfaceDescription = interfaces.get(0);
        assertThat(interfaceDescription.getName(), equalTo("eth0"));
        assertThat(interfaceDescription.getEndpoint(), equalTo(InterfaceDescription.Endpoint.PHYSICAL));
    }
}
