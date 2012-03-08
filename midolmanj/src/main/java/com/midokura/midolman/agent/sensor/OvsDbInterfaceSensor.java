/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package com.midokura.midolman.agent.sensor;

import com.google.inject.Inject;
import com.midokura.midolman.agent.interfaces.InterfaceDescription;
import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnection;

import java.util.List;

public class OvsDbInterfaceSensor implements InterfaceSensor {

    @Inject
    OpenvSwitchDatabaseConnection ovsdb;

    @Override
    public List<InterfaceDescription> updateInterfaceData(List<InterfaceDescription> interfaces) {
        for (InterfaceDescription interfaceDescription : interfaces) {
            if (interfaceDescription.getEndpoint() != InterfaceDescription.Endpoint.UNKNOWN) {
                // We already got an endpoint classification from the previous sensor
                // Skip this interface
                continue;
            }

            if (ovsdb.hasBridge(interfaceDescription.getName())) {
                // this is a bridge interface
                interfaceDescription.setEndpoint(InterfaceDescription.Endpoint.BRIDGE);
                continue;
            }

            if (ovsdb.hasPort(interfaceDescription.getName())) {
                // this interface is a port
                continue;
            }

            //if (ovsdb.)

            //String portUUID = ovsdb.getPortUUID(interfaceDescription.getName());
            //System.out.println("Port " + interfaceDescription.getName() + " UUID " + portUUID);
        }
        return interfaces;
    }
}
