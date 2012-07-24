/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.netlink.dp.flows;

/**
* Enum that encapsulates the types of IP Fragments available. Can convert to
 * and from a byte value to be used in the FlowKeyIPv4, FlowKeyIPv6.
*/
public enum IPFragmentType {

    None(0), First(1), Later(2);

    byte value;

    IPFragmentType(int value) {
        this.value = (byte) value;
    }

    static IPFragmentType fromByte(byte value) {
        switch (value) {
            case 0:
                return None;
            case 1:
                return First;
            case 2:
                return Later;
            default:
                return null;
        }
    }

    static byte toByte(IPFragmentType fragmentType) {
        if ( fragmentType == null )
            return 0;

        return fragmentType.value;
    }
}
