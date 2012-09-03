/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midonet.cluster.data;

import java.util.UUID;
import javax.annotation.Nonnull;

/**
 *
 */
public abstract class TunnelZone<
    Zone extends TunnelZone<Zone, ZoneData>,
    ZoneData extends TunnelZone.Data
    > extends Entity.Base<UUID, ZoneData, Zone> {

    public static enum Type {
        Gre, Ipsec, Capwap
    }

    public abstract Type getType();

    protected TunnelZone(UUID uuid, @Nonnull ZoneData data) {
        super(uuid, data);
    }

    public Zone setName(String name) {
        getData().name = name;
        return self();
    }

    public String getName() {
        return getData().name;
    }

    public static class Data {
        String name;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            Data data = (Data) o;

            if (name != null ? !name.equals(data.name) : data.name != null)
                return false;

            return true;
        }

        @Override
        public int hashCode() {
            return name != null ? name.hashCode() : 0;
        }

        @Override
        public String toString() {
            return "Data{" +
                "name='" + name + '\'' +
                '}';
        }
    }

    public abstract static class HostConfig<
        ActualHostConfig extends HostConfig<ActualHostConfig, HostConfigData>,
        HostConfigData extends HostConfig.Data>
        extends Entity.Base<UUID, HostConfigData, ActualHostConfig> {

        protected HostConfig(UUID uuid, @Nonnull HostConfigData hostConfigData) {
            super(uuid, hostConfigData);
        }

        public static class Data {

        }
    }
}
