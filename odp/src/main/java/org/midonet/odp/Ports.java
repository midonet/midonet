/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.odp;

import javax.annotation.Nonnull;

import org.midonet.odp.ports.*;

/**
 * Helper class that acts as a factory to port types.
 */
public class Ports {

    public static InternalPort newInternalPort(@Nonnull String name) {
        return new InternalPort(name);
    }

    public static NetDevPort newNetDevPort(@Nonnull String name) {
        return new NetDevPort(name);
    }

    public static GreTunnelPort newGreTunnelPort(@Nonnull String name) {
        return new GreTunnelPort(name);
    }

    public static VxLanTunnelPort newVxLanTunnelPort(@Nonnull String name) {
        return new VxLanTunnelPort(name);
    }

    public static Port<?,?> newPortByType(Port.Type type, String name) {
        if (type == null || name == null)
            return null;

        switch (type) {
            case NetDev:
                return new NetDevPort(name);
            case Internal:
                return new InternalPort(name);
            case Gre:
                return new GreTunnelPort(name);
            case Gre101:
                return new GreTunnelPort(name);
            case Gre64:
                return new GreTunnelPort(name);
            case VXLan:
                return new VxLanTunnelPort(name);
            default:
                return null;
        }
    }

    public static Port<?,?> newPortByTypeId(Integer type, String name) {
        if (type == null || name == null)
            return null;

        switch (type) {

            case OpenVSwitch.Port.Type.Netdev:
                return new NetDevPort(name);

            case OpenVSwitch.Port.Type.Internal:
                return new InternalPort(name);

            case OpenVSwitch.Port.Type.Gre:
                return new GreTunnelPort(name);

            case OpenVSwitch.Port.Type.GreOld:
                return new GreTunnelPort(name);

            case OpenVSwitch.Port.Type.Gre64:
                return new GreTunnelPort(name);

            case OpenVSwitch.Port.Type.VXLan:
                return new VxLanTunnelPort(name);

            default:
                return null;
        }
    }

}
