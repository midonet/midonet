// Copyright 2012 Midokura Inc.

package org.midonet.packets;


import org.codehaus.jackson.annotate.JsonSubTypes;
import org.codehaus.jackson.annotate.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY,
    property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = IPv4Subnet.class, name = "IPv4"),
    @JsonSubTypes.Type(value = IPv6Subnet.class, name = "IPv6")
})
public interface IPSubnet<T extends IPAddr> {
    T getAddress();
    int getPrefixLen();
    boolean containsAddress(IPAddr addr);
    String toString();
    String toZkString();
    /* Required for deserialization */
    void setAddress(T address);
    /* Required for deserialization */
    void setPrefixLen(int prefixLen);
    short ethertype();
}
