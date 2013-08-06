/*
 * Copyright 2012 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.api.network;

import org.midonet.cluster.Client;
import org.midonet.cluster.data.Port.Property;
import org.midonet.cluster.data.ports.*;

public class PortFactory {

    public static Port convertToApiPortV1(org.midonet.cluster.data.Port data) {
        //TODO av-mido: This is for backwards compatibility, the new api
        //TODO   will just return BridgePort or RouterPort.
        if (data instanceof org.midonet.cluster.data.ports.RouterPort) {
            if (data.isExterior()
                  || (data.isUnplugged()
                      && Client.PortType.ExteriorRouter.toString()
                         .equals(data.getProperty(Property.v1PortType)))) {
                return new ExteriorRouterPort((org.midonet.cluster.data.ports
                        .RouterPort) data);
            } else {
                return new InteriorRouterPort(
                        (org.midonet.cluster.data.ports.RouterPort)data);
            }
        } else if (data instanceof org.midonet.cluster.data.ports.BridgePort) {
            if(data.isExterior()
                  || (data.isUnplugged()
                      && Client.PortType.ExteriorBridge.toString()
                         .equals(data.getProperty(Property.v1PortType)))) {
                return new ExteriorBridgePort((org.midonet.cluster.data.ports
                        .BridgePort) data);
            } else {
                return new InteriorBridgePort(
                        (org.midonet.cluster.data.ports.BridgePort)data);
            }
        } else {
            throw new UnsupportedOperationException(
                    "Cannot instantiate this port type.");
        }
    }

    public static Port convertToApiPort(org.midonet.cluster.data.Port data) {
        if (data instanceof org.midonet.cluster.data.ports.RouterPort) {
            return new RouterPort(
                    (org.midonet.cluster.data.ports.RouterPort)data);
        } else if (data instanceof org.midonet.cluster.data.ports.BridgePort) {
            return new BridgePort(
                    (org.midonet.cluster.data.ports.BridgePort)data);
        } else {
            throw new UnsupportedOperationException(
                    "Cannot instantiate this port type.");
        }
    }
}
