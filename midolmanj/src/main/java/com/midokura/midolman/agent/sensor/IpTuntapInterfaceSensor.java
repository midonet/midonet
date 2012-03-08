/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package com.midokura.midolman.agent.sensor;

import com.midokura.midolman.agent.interfaces.InterfaceDescription;

import java.util.List;

public class IpTuntapInterfaceSensor implements InterfaceSensor{

    @Override
    public List<InterfaceDescription> updateInterfaceData(List<InterfaceDescription> interfaces) {
        for (InterfaceDescription interfaceDescription : interfaces) {
            // Only update endpoints to those interfaces who don't already have it
            if (interfaceDescription.getEndpoint() == InterfaceDescription.Endpoint.UNKNOWN) {
                // Is this a Tuntap interface?
                if (isTuntap(interfaceDescription.getName())) {
                    interfaceDescription.setEndpoint(InterfaceDescription.Endpoint.TUNTAP);
                }
            }
        }
        return interfaces;
    }

    protected List<String> getTuntapOutput() {
        return ProcessHelper.executeCommandLine("ip tuntap");
    }

    private boolean isTuntap (String interfaceName) {
        for (String line : getTuntapOutput()) {
            String[] tokens = line.trim().split(":");
            if (tokens.length < 1) {
                return false;
            }

            if (tokens[0].equals(interfaceName)) {
                return true;
            }
        }
        return false;
    }
}
