/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.client.dto;

import java.util.UUID;

public class DtoIpv6AddrGroupAddr extends DtoIpAddrGroupAddr {

    public DtoIpv6AddrGroupAddr(){
        super();
    }

    public DtoIpv6AddrGroupAddr(UUID groupId, String addr) {
        super(groupId, addr);
    }

    @Override
    public int getVersion() {
        return 6;
    }
}
