/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package com.midokura.midolman.agent.sensor;

import com.midokura.midolman.agent.interfaces.InterfaceDescription;

import java.util.List;

public class DmesgInterfaceSensor implements InterfaceSensor {

    @Override
    public List<InterfaceDescription> updateInterfaceData(List<InterfaceDescription> interfaces) {
        for (InterfaceDescription interfaceDescription : interfaces) {
            // Only update those interfaces who don't already have the endpoint set
            if (interfaceDescription.getEndpoint() == InterfaceDescription.Endpoint.UNKNOWN) {
                // Is this a physical interface?
                if (isPhysical(interfaceDescription.getName())) {
                    interfaceDescription.setEndpoint(InterfaceDescription.Endpoint.PHYSICAL);
                }
            }
        }
        return interfaces;
    }

    protected List<String> getDmesgOutput(String interfaceName) {
        return ProcessHelper.executeCommandLine("dmesg | grep " + interfaceName + " | grep PHY");
    }

    private boolean isPhysical(String interfaceName) {
        // Just check if there's a line in the output, that means the interface is physical
        return (getDmesgOutput(interfaceName).size() == 1);
    }
}
