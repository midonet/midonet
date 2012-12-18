/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.odp;

import javax.annotation.Nonnull;

import com.midokura.odp.ports.CapWapTunnelPort;
import com.midokura.odp.ports.CapwapTunnelPortOptions;
import com.midokura.odp.ports.GreTunnelPort;
import com.midokura.odp.ports.GreTunnelPortOptions;
import com.midokura.odp.ports.InternalPort;
import com.midokura.odp.ports.InternalPortOptions;
import com.midokura.odp.ports.NetDevPort;
import com.midokura.odp.ports.NetDevPortOptions;
import com.midokura.odp.ports.PatchTunnelPort;
import com.midokura.odp.ports.PatchTunnelPortOptions;
import com.midokura.odp.ports.TunnelPortOptions;

/**
 * Helper class that acts as a factory to port types.
 */
public class Ports {

    public static PatchTunnelPortOptions newPortOptions(PatchTunnelPort port, String peerName) {
        port.setOptions(new PatchTunnelPortOptions());
        return port.getOptions().setPeer(peerName);
    }

    public static GreTunnelPortOptions newPortOptions(GreTunnelPort port,
                                                       int destination,
                                                       TunnelPortOptions.Flag... flags) {
        port.setOptions(new GreTunnelPortOptions());
        return port.getOptions()
                   .setDestinationIPv4(destination)
                   .setFlags(flags);
    }

    public static CapwapTunnelPortOptions newPortOptions(CapWapTunnelPort port,
                                                          int destination,
                                                          TunnelPortOptions.Flag... flags) {
        port.setOptions(new CapwapTunnelPortOptions());
        return port
            .getOptions()
            .setDestinationIPv4(destination)
            .setFlags(flags);
    }

    public static InternalPortOptions newPortOptions(InternalPort port) {
        port.setOptions(new InternalPortOptions());
        return port.getOptions();
    }

    public static NetDevPortOptions newPortOptions(NetDevPort port) {
        port.setOptions(new NetDevPortOptions());
        return port.getOptions();
    }

    public static InternalPort newInternalPort(@Nonnull String name) {
        return new InternalPort(name);
    }

    public static NetDevPort newNetDevPort(@Nonnull String name) {
        return new NetDevPort(name);
    }

    public static PatchTunnelPort newPatchTunnelPort(@Nonnull String name) {
        return new PatchTunnelPort(name);
    }

    public static GreTunnelPort newGreTunnelPort(@Nonnull String name) {
        return new GreTunnelPort(name);
    }

    public static CapWapTunnelPort newCapwapTunnelPort(@Nonnull String name) {
        return new CapWapTunnelPort(name);
    }

    public static Port newPortByType(Port.Type type, String name) {
        if (type == null || name == null)
            return null;

        switch (type) {
            case NetDev:
                return new NetDevPort(name);
            case CapWap:
                return new CapWapTunnelPort(name);
            case Gre:
                return new GreTunnelPort(name);
            case Internal:
                return new InternalPort(name);
            case Patch:
                return new PatchTunnelPort(name);
            default:
                return null;
        }
    }
}
