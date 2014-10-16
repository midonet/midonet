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
