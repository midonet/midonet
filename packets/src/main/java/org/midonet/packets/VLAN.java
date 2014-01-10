/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.packets;

public final class VLAN {
    private VLAN() { }

    /**
     * Drop Eligible Indicator (DEI): a 1-bit field used to indicate frames
     * eligible to be dropped in the presence of congestion.
     */

    private static int DEI_MASK = 0x1000;

    public static short setDEI(short vlanTCI) {
        return (short) (vlanTCI | DEI_MASK);
    }

    public static short unsetDEI(short vlanTCI) {
        return (short) (vlanTCI & ~DEI_MASK);
    }
}
