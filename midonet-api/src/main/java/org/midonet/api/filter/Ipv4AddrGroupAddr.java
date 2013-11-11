/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.api.filter;

import java.util.UUID;

public class Ipv4AddrGroupAddr extends IpAddrGroupAddr {

    public Ipv4AddrGroupAddr(){
        super();
    }

    public Ipv4AddrGroupAddr(UUID groupId, String addr) {
        super(groupId, addr);
    }

    @Override
    public int getVersion() {
        return 4;
    }
}
