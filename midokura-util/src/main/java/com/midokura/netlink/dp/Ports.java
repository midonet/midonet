/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.netlink.dp;

import javax.annotation.Nonnull;

import com.midokura.netlink.dp.ports.CapWapTunnelPort;
import com.midokura.netlink.dp.ports.GreTunnelPort;
import com.midokura.netlink.dp.ports.InternalPort;
import com.midokura.netlink.dp.ports.NetDevPort;
import com.midokura.netlink.dp.ports.PatchTunnelPort;
import com.midokura.netlink.dp.ports.TunnelPortOptions;

/**
 * Helper class that acts as a factory to port types.
 */
public class Ports {

    public static PatchTunnelPort.Options newPortOptions(PatchTunnelPort port, String peerName) {
        port.setOptions(port.new Options());
        return port.getOptions().setPeer(peerName);
    }

    public static GreTunnelPort.Options newPortOptions(GreTunnelPort port,
                                                       int destination,
                                                       TunnelPortOptions.Flag... flags) {
        port.setOptions(port.new Options());
        return port.getOptions()
                   .setDestinationIPv4(destination)
                   .setFlags(flags);
    }

    public static CapWapTunnelPort.Options newPortOptions(CapWapTunnelPort port,
                                                          int destination,
                                                          TunnelPortOptions.Flag... flags) {
        port.setOptions(port.new Options());
        return port
            .getOptions()
            .setDestinationIPv4(destination)
            .setFlags(flags);
    }

    public static InternalPort.Options newPortOptions(InternalPort port) {
        port.setOptions(port.new Options());
        return port.getOptions();
    }

    public static NetDevPort.Options newPortOptions(NetDevPort port) {
        port.setOptions(port.new Options());
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
