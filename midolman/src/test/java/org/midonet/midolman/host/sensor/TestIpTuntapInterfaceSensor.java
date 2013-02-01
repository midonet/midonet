/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package org.midonet.midolman.host.sensor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

import org.midonet.midolman.host.interfaces.InterfaceDescription;

public class TestIpTuntapInterfaceSensor {

    @Test
    public void testUpdateInterfaceData() throws Exception {

        InterfaceSensor interfaceSensor = new IpTuntapInterfaceSensor() {
            @Override
            protected List<String> getTuntapOutput() {
                return Arrays.asList(
                    "xxx: tun",
                    "midovpn1: tap one_queue",
                    "midovpn3: tap one_queue",
                    "tapBridge4: tap",
                    "newRouterPort: tap"
                );
            }
        };

        List<InterfaceDescription> interfaces = new ArrayList<InterfaceDescription>();

        // Add one interface to test
        interfaces.add(new InterfaceDescription("xxx"));

        assertThat(
            "The end point was not updated",
            interfaces.get(0).getEndpoint(),
            is(InterfaceDescription.Endpoint.UNKNOWN));

        // Do the interface updating
        interfaces = interfaceSensor.updateInterfaceData(interfaces);

        //Check that we parsed all the interfaces
        assertThat(interfaces.size(), equalTo(1));

        assertThat(
            "The interface didn't have it's name changed",
            interfaces.get(0).getName(), is("xxx"));

        assertThat(
            "The end point is parsed correctly",
            interfaces.get(0).getEndpoint(),
            is(InterfaceDescription.Endpoint.TUNTAP));
    }

    @Test
    public void testDontUpdateNonTapInterfaceData() throws Exception {

        InterfaceSensor interfaceSensor = new IpTuntapInterfaceSensor() {
            @Override
            protected List<String> getTuntapOutput() {
                return Arrays.asList(
                    "xxy: tun",
                    "midovpn1: tap one_queue",
                    "midovpn3: tap one_queue",
                    "tapBridge4: tap",
                    "newRouterPort: tap"
                );
            }
        };

        List<InterfaceDescription> interfaces = new ArrayList<InterfaceDescription>();

        // Add one interface to test
        interfaces.add(new InterfaceDescription("bibiri"));

        // Do the interface updating
        interfaces = interfaceSensor.updateInterfaceData(interfaces);

        //Check that we parsed all the interfaces
        assertThat(interfaces.size(), equalTo(1));

        assertThat(
            "The end point was not updated",
            interfaces.get(0).getEndpoint(),
            is(InterfaceDescription.Endpoint.UNKNOWN));

        // Do the interface updating
        interfaces = interfaceSensor.updateInterfaceData(interfaces);

        //Check that we parsed all the interfaces
        assertThat(interfaces.size(), equalTo(1));

        // Check the interface
        assertThat(
            "The interface didn't have it's name changed",
            interfaces.get(0).getName(), is("bibiri"));

        assertThat(
            "The end point was not updated",
            interfaces.get(0).getEndpoint(), is(
            InterfaceDescription.Endpoint.UNKNOWN));
    }
}
