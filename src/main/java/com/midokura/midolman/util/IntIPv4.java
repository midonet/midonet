package com.midokura.midolman.util;

public class IntIPv4 implements Cloneable{
    public final int address;

    public IntIPv4(int addr) {
        address = addr;
    }

    public IntIPv4 clone() { return new IntIPv4(address); }

    public static IntIPv4 fromString(String dottedQuad) {
        return new IntIPv4(Net.convertStringAddressToInt(dottedQuad));
    }

    @Override
    public String toString() {
        return Net.convertIntAddressToString(address);
    }

    @Override
    public boolean equals(Object rhs) {
        if (this == rhs)
            return true;
        if (! (rhs instanceof IntIPv4))
            return false;
        return address == ((IntIPv4)rhs).address;
    }

    @Override
    public int hashCode() {
        return address;
    }
}
