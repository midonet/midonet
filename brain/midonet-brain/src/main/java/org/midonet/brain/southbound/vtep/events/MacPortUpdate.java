/**
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.brain.southbound.vtep.events;

import org.midonet.packets.IPv4Addr;
import org.midonet.packets.MAC;

/**
 * This class represents an event corresponding to an update to any of the
 * MAC-port tables that may be relevant for a VTEP. Eg. changes in MidoNet
 * MAC-port tables, changes in VTEP internal configurations.
 *
 * Regardless of the device (real as with an actual VTEP switch, or virtual as
 * with MidoNet), we associate a given MAC address to a VTEP's tunnel IP. The
 * device from which this update originates from will tunnel packets addressed
 * to the MAC address towards the specified IP address. Note that VNI is not a
 * concern of the MAC-port update itself because the VNI is injected by the
 * VTEP (or MidoNet acting as such) based on the port configurations.
 *
 * A MacPortUpdate(mac, oldVtepIp, newVtepIp) means that the entry corresponding
 * to MAC changed from oldVtepIp to newVtepIp. If oldVtepIp is null, then it
 * means that a MAC-port entry was added. If oldVtepIp is not null but the
 * newVtepIP is null, it means that a MAC-port entry is deleted, and if both are
 * non-null, then it means that a MAC is re-assigned to a new IP address.
 */
public class MacPortUpdate {
    public final MAC mac;
    public final IPv4Addr oldVtepIp;
    public final IPv4Addr newVtepIp;

    public MacPortUpdate(String mac, String oldVtepIp, String newVtepIp) {
        this.mac = MAC.fromString(mac);
        this.oldVtepIp = oldVtepIp == null ? null
                                           : IPv4Addr.fromString(oldVtepIp);
        this.newVtepIp = newVtepIp == null ? null
                                           : IPv4Addr.fromString(newVtepIp);
    }

    public MacPortUpdate(final MAC mac, final IPv4Addr oldVtepIp,
                         final IPv4Addr newVtepIp) {
        this.mac = new MAC(mac.asLong());
        this.oldVtepIp = oldVtepIp;
        this.newVtepIp = newVtepIp;
    }

    /**
     * Returns whether if this is a new MAC entry addition.
     * @return True if this is a new MAC entry addition, false otherwise.
     */
    public boolean isAdd() {
        return this.oldVtepIp == null && this.newVtepIp != null;
    }

    /**
     * Returns whether if this is an existing MAC entry update.
     * @return True if this is an existing MAC entry update, false otherwise.
     */
    public boolean isUpdate() {
        return this.oldVtepIp != null && this.newVtepIp != null;
    }

    /**
     * Returns whether if this is a MAC entry deletion.
     * @return True if this is a MAC entry deletion, false otherwise.
     */
    public boolean isDelete() {
        return this.oldVtepIp != null && this.newVtepIp == null;
    }

    public static String toMacPortUpdateStr(
            MAC mac, IPv4Addr oldVtepIp, IPv4Addr newVtepIp) {
        return mac + "," + oldVtepIp + "," + newVtepIp;
    }

    @Override
    public String toString() {
        return MacPortUpdate.toMacPortUpdateStr(mac, oldVtepIp, newVtepIp);
    }
}
