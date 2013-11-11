/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.api.filter;

import org.codehaus.jackson.annotate.JsonSubTypes;
import org.codehaus.jackson.annotate.JsonTypeInfo;
import org.midonet.api.ResourceUriBuilder;
import org.midonet.api.UriResource;
import org.midonet.util.version.Since;

import javax.validation.GroupSequence;
import javax.validation.constraints.AssertTrue;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlTransient;
import java.net.URI;
import java.util.UUID;

public class Ipv6AddrGroupAddr extends IpAddrGroupAddr {

    public Ipv6AddrGroupAddr(){
        super();
    }

    public Ipv6AddrGroupAddr(UUID groupId, String addr) {
        super(groupId, addr);
    }

    @Override
    public int getVersion() {
        return 6;
    }
}
