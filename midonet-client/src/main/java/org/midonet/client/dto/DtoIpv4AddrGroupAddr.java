/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.client.dto;

import java.util.UUID;

public class DtoIpv4AddrGroupAddr extends DtoIpAddrGroupAddr {

    public DtoIpv4AddrGroupAddr(){
        super();
    }

    public DtoIpv4AddrGroupAddr(UUID groupId, String addr) {
        super(groupId, addr);
    }

    @Override
    public int getVersion() {
        return 4;
    }
}
