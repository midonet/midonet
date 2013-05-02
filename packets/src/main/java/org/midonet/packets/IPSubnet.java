// Copyright 2012 Midokura Inc.

package org.midonet.packets;


public interface IPSubnet<T extends IPAddr> {
    T getAddress();
    int getPrefixLen();
    boolean containsAddress(T addr);
    String toString();
    String toZkString();
    /* Required for deserialization */
    void setAddress(T address);
    /* Required for deserialization */
    void setPrefixLen(int prefixLen);
}
