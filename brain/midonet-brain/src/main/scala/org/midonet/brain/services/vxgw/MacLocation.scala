/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.brain.services.vxgw

import org.midonet.brain.southbound.vtep.VtepMAC
import org.midonet.packets.IPv4Addr

import com.google.common.base.Objects


/**
 * Represents the association of a MAC in the given logical switch to one
 * VxLAN Tunnel Endpoint with the given IP.
 *
 * @param mac is the MAC address to which the rest of information relates.
 * @param ipAddr is an IP that has been associated to the MAC (either
 *               learned or explicitly set. Can be null.
 * @param logicalSwitchName  is the name of the logical switch (usually the
 *                           midonet bridge uuid prefixed by "mn-".
 * @param vxlanTunnelEndpoint  is the tunnel ip for the host/vtep containing
 *                             the port associated to the MAC. It can be null,
 *                             indicating that the MAC is no longer associated
 *                             to a particular port or a particular IP.
 */
class MacLocation (val mac: VtepMAC,
                   val ipAddr: IPv4Addr,
                   val logicalSwitchName: String,
                   val vxlanTunnelEndpoint: IPv4Addr) {

    if (mac == null)
        throw new IllegalArgumentException("MAC cannot be null")

    if (logicalSwitchName == null)
        throw new IllegalArgumentException("Logical Switch cannot be null")

    override def hashCode() = Objects.hashCode(mac, ipAddr, logicalSwitchName,
                                               vxlanTunnelEndpoint)

    override def toString = "MacLocation{" +
                            "logicalSwitchName=" + logicalSwitchName +
                            ", mac=" + mac +
                            ", ipAddr=" + ipAddr +
                            ", vxlanTunnelEndpoint=" + vxlanTunnelEndpoint + "}"

    override def equals(o: Any): Boolean = {
        o match {
            case that: MacLocation =>
                Objects.equal(mac, that.mac) &&
                Objects.equal(logicalSwitchName, that.logicalSwitchName) &&
                Objects.equal(ipAddr, that.ipAddr) &&
                Objects.equal(vxlanTunnelEndpoint, that.vxlanTunnelEndpoint)
            case _ => false
        }
    }

}
