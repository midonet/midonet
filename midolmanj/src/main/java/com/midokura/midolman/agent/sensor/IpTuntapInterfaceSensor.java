/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package com.midokura.midolman.agent.sensor;

import java.util.List;

import com.midokura.midolman.agent.interfaces.InterfaceDescription;
import com.midokura.util.process.ProcessHelper;
import static com.midokura.midolman.agent.interfaces.InterfaceDescription.Endpoint;
import static com.midokura.midolman.agent.interfaces.InterfaceDescription.Type;

public class IpTuntapInterfaceSensor implements InterfaceSensor{

    @Override
    public List<InterfaceDescription> updateInterfaceData(List<InterfaceDescription> interfaces) {
        for (InterfaceDescription interfaceDescription : interfaces) {
            // Only update endpoints to those interfaces who don't already have it
            if (interfaceDescription.getEndpoint() == Endpoint.UNKNOWN) {
                // Is this a Tuntap interface?
                if (isTuntap(interfaceDescription.getName())) {
                    interfaceDescription.setEndpoint(Endpoint.TUNTAP);
                    interfaceDescription.setType(Type.VIRT);
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
