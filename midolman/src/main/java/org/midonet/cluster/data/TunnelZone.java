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

import java.util.UUID;
import javax.annotation.Nonnull;

import org.codehaus.jackson.annotate.JsonProperty;

import org.midonet.packets.IPv4Addr;

public class TunnelZone extends Entity.Base<UUID, TunnelZone.Data, TunnelZone> {

    public static enum Type {
        gre, vxlan, vtep
    }

    public Type getType() {
        return getData().type;
    }

    public TunnelZone setType(Type type) {
        getData().type = type;
        return self();
    }

    public TunnelZone(UUID uuid, @Nonnull Data data) {
        super(uuid, data);
    }

    public TunnelZone() {
        super(null, new Data());
    }

    @Override
    protected TunnelZone self() {
        return this;
    }

    public TunnelZone setName(String name) {
        getData().name = name;
        return self();
    }

    public String getName() {
        return getData().name;
    }

    public static class Data {
        String name;

        @JsonProperty("@type")
        Type type;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            Data data = (Data) o;

            if (name != null ? !name.equals(data.name) : data.name != null)
                return false;

            if (type != null ? !type.equals(data.type) : data.type != null)
                return false;

            return true;
        }

        @Override
        public int hashCode() {
            int result = name != null ? name.hashCode() : 0;
            return 31 * result +
                   (type != null ? type.hashCode() : 0);
        }

        @Override
        public String toString() {
            return "Data{" +
                "name='" + name + '\'' +
                ",type='" + type + '\'' +
                '}';
        }
    }


    public static class HostConfig extends Entity.Base<UUID, HostConfig.Data, HostConfig> {

        public HostConfig(UUID uuid, @Nonnull HostConfig.Data hostConfigData) {
            super(uuid, hostConfigData);
        }

        public HostConfig(UUID uuid) {
            super(uuid, new Data());
        }

        public HostConfig() {
            super(null, new Data());
        }

        public HostConfig setIp(IPv4Addr ip) {
            getData().ip = ip;
            return self();
        }

        @Override
        public HostConfig self() {
            return this;
        }

        public IPv4Addr getIp() {
            return getData().ip;
        }

        /*
         * For backwards compatibility, since zone members are not really typed
         * anymore, hard-code type to gre.
         */
        public String getType() { return "gre"; }

        public void setType(String type) {
            // IGNORE
        }

        public static class Data {
            IPv4Addr ip;

            @JsonProperty("@type")
            final String type = "gre";

            @Override
            public boolean equals(Object o) {
                if (this == o) return true;
                if (o == null || getClass() != o.getClass()) return false;

                Data data = (Data) o;

                if (ip != null ? !ip.equals(data.ip) : data.ip != null)
                    return false;

                return true;
            }

            @Override
            public int hashCode() {
                return ip != null ? ip.hashCode() : 0;
            }

            @Override
            public String toString() {
                return "Data{" + "ip=" + ip + '}';
            }
        }
    }
}
