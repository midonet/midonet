// Copyright 2012 Midokura Inc.

package org.midonet.packets;


public interface IPSubnet {
    IPAddr getAddress();
    int getPrefixLen();
    boolean containsAddress(IPAddr addr);
    String toString();
    String toZkString();
    IntIPv4 toIntIPv4();
}
