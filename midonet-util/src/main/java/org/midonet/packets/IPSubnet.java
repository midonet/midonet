/*
 * Copyright 2014 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.midonet.packets;


import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import org.midonet.Util;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY,
    property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = IPv4Subnet.class, name = "IPv4"),
    @JsonSubTypes.Type(value = IPv6Subnet.class, name = "IPv6")
})
public abstract class IPSubnet<T extends IPAddr> {

    protected T address;
    protected int prefixLen;

    protected IPSubnet() {}

    protected IPSubnet(T address, int prefixLen) {
        this.address = address;
        this.prefixLen = prefixLen;
    }

    public T getAddress() {
        return address;
    }

    public int getPrefixLen() {
        return prefixLen;
    }

    public abstract boolean containsAddress(IPAddr addr);

    /* Required for deserialization */
    public abstract void setAddress(T address);

    /* Required for deserialization */
    public void setPrefixLen(int prefixLen) {
        this.prefixLen = prefixLen;
    }

    public abstract short ethertype();

    @Override
    public String toString() {
        return address.toString() + "/" + prefixLen;
    }

    public String toZkString() {
        return address.toString() + "_" + prefixLen;
    }

    public String toUnicastString() {
        return getAddress().toString();
    }

    public static IPSubnet<?> fromString(String cidr) {
        return cidr.contains(".") ?
               IPv4Subnet.fromCidr(cidr) : IPv6Subnet.fromString(cidr);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || this.getClass() != o.getClass())
            return false;

        IPSubnet<T> that = Util.uncheckedCast(o);
        return prefixLen == that.prefixLen &&
               Objects.equals(address, that.address);
    }

    @Override
    public int hashCode() {
        return Objects.hash(address, prefixLen);
    }
}
