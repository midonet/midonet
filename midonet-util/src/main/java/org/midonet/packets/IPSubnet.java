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

import org.apache.commons.lang.StringUtils;

import org.midonet.Util;

public abstract class IPSubnet<T extends IPAddr> {

    protected final T address;
    protected final int prefixLength;
    private String string = null;

    protected IPSubnet(T address, int prefixLength) {
        this.address = address;
        this.prefixLength = prefixLength;
    }

    public T getAddress() {
        return address;
    }

    public int getPrefixLen() {
        return prefixLength;
    }

    public abstract boolean containsAddress(IPAddr address);

    public abstract T toNetworkAddress();

    public abstract T toBroadcastAddress();

    public abstract short ethertype();

    @Override
    public String toString() {
        if (string == null)
            string = address.toString() + "/" + prefixLength;
        return string;
    }

    public String toUriString() {
        return address.toString() + "_" + prefixLength;
    }

    public String toUnicastString() {
        return getAddress().toString();
    }

    public static IPSubnet<?> fromString(String address, int prefixLength) {
        return StringUtils.contains(address, '.') ?
               new IPv4Subnet(address, prefixLength) :
               new IPv6Subnet(address, prefixLength);
    }

    public static IPSubnet<?> fromCidr(String cidr) {
        return StringUtils.contains(cidr, '.') ?
               IPv4Subnet.fromCidr(cidr) : IPv6Subnet.fromCidr(cidr);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || this.getClass() != o.getClass())
            return false;

        IPSubnet<T> that = Util.uncheckedCast(o);
        return prefixLength == that.prefixLength &&
               Objects.equals(address, that.address);
    }

    @Override
    public int hashCode() {
        return Objects.hash(address, prefixLength);
    }
}
