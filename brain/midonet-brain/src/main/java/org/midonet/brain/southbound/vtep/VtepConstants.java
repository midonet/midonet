/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.brain.southbound.vtep;

import java.util.UUID;

/**
 * Various constants and utility functions related to the interaction between
 * a VTEP and Midonet.
 */
public class VtepConstants {

    /**
     * This constant is defined in the OVSDB spec for the VTEP schema, it is
     * used to designate a "unknown-dst" mac in mcast_remote tables. Refer to
     * http://openvswitch.org/docs/vtep.5.pdf for further details.
     */
    public static final String UNKNOWN_DST = "unknown-dst";

    /**
     * This is the prefix prepended to the bridge uuid when composing a logical
     * switch name.
     */
    private static final String LOGICAL_SWITCH_PREFIX = "mn-";

    /**
     * Logical switches formed to bind a VTEP port and a Midonet bridge have a
     * name formed as a function of the bridge uuid. This function extracts the
     * uuid from the logical switch name.
     *
     * @param lsName the logical switch name
     * @return the Midonet bridge UUID
     */
    public static UUID logicalSwitchNameToBridgeId(String lsName) {
        try {
            return UUID.fromString(
                lsName.substring(LOGICAL_SWITCH_PREFIX.length())
            );
        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * This function yields the name that must be assigned to a logical switch
     * used to bind a VTEP's port with a Midonet bridge.
     *
     * @param uuid the bridge's identifier
     * @return the logical switch name
     */
    public static String bridgeIdToLogicalSwitchName(UUID uuid) {
        return LOGICAL_SWITCH_PREFIX + uuid;
    }
}
