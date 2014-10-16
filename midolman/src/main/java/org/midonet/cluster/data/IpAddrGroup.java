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
package org.midonet.cluster.data;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/***
 * IP Address Group. There's no Ipv4AddrGroup or Ipv6AddrGroup because
 * IP address groups can contain both types of IP addresses.
 */
public class IpAddrGroup
        extends Entity.Base<UUID, IpAddrGroup.Data, IpAddrGroup> {

    public IpAddrGroup() {
        this(null, new Data());
    }

    public IpAddrGroup(UUID id){
        this(id, new Data());
    }

    public IpAddrGroup(Data data){
        this(null, data);
    }

    public IpAddrGroup(UUID uuid, Data data) {
        super(uuid, data);
    }

    @Override
    protected IpAddrGroup self() {
        return this;
    }

    public String getName() {
        return getData().name;
    }

    public IpAddrGroup setName(String name) {
        getData().name = name;
        return this;
    }

    public IpAddrGroup setProperties(Map<String, String> properties) {
        getData().properties = properties;
        return this;
    }

    public Map<String, String> getProperties() {
        return getData().properties;
    }

    public static class Data {
        public String name;
        public Map<String, String> properties = new HashMap<String, String>();

        @Override
        public boolean equals(Object o) {
            if (this == o)
                return true;
            if (o == null || getClass() != o.getClass())
                return false;

            Data that = (Data) o;

            if (name != null ? !name.equals(that.name) : that.name != null)
                return false;

            return true;
        }

        @Override
        public int hashCode() {
            return (name != null) ? name.hashCode() : 0;
        }

        @Override
        public String toString() {
            return "IpAddrGroup " + "{name=" + name + '}';
        }
    }
}
