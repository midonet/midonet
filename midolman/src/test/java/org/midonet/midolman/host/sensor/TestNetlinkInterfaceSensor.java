/*
 * Copyright 2014 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
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
