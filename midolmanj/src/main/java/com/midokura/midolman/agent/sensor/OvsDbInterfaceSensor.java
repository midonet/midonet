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


            // Only update interfaces were the endpoint hasn't been already set
            if (interfaceDescription.getEndpoint() != InterfaceDescription.Endpoint.UNKNOWN) {
                // We already got an endpoint classification from the previous sensor
                // Skip this interface
                continue;
            }

            if (ovsdb.hasBridge(interfaceDescription.getName())) {
                // this is a bridge interface
                interfaceDescription.setEndpoint(InterfaceDescription.Endpoint.BRIDGE);
            }
        }

        return interfaces;
    }
}
