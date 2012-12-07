// Copyright 2012 Midokura Inc.

package com.midokura.packets;


public interface IPSubnet {
    IPAddr getAddress();
    int getPrefixLen();
    boolean containsAddress(IPAddr addr);
    String toString();
    String toZkString();
}
