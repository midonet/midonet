/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.cluster.client;

import java.util.UUID;

import org.midonet.cluster.data.TunnelZone;
import org.midonet.cluster.data.zones.*;
import org.midonet.packets.IPv4;

public interface TunnelZones {

    interface BuildersProvider {
        GreBuilder getGreZoneBuilder();
    }

    /**
     * This is what an tunnel zone Builder should look like:
     * - it needs to have a Zone Configuration
     * - it needs to have a per host Configuration type
     *
     * @param <ZoneConfigType>  is the type of the zone configuration
     * @param <HostConfigType>  is the type of the per host configuration
     * @param <ConcreteBuilder> is the actual interface providing the previous types
     */
    interface Builder<
        ZoneConfigType extends Builder.ZoneConfig,
        HostConfigType,
        ConcreteBuilder extends Builder<
            ZoneConfigType, HostConfigType, ConcreteBuilder>
        >
        extends org.midonet.cluster.client.Builder<ConcreteBuilder> {

        public interface ZoneConfig<T extends TunnelZone<T, ?>> {
            T getTunnelZoneConfig();
        }

        public interface HostConfig {
        }

        ConcreteBuilder setConfiguration(ZoneConfigType configuration);

        ConcreteBuilder addHost(UUID hostId, HostConfigType hostConfig);

        ConcreteBuilder removeHost(UUID hostId, HostConfigType hostConfig);
    }

    interface GreBuilder extends Builder<
        GreBuilder.ZoneConfig, GreTunnelZoneHost, GreBuilder> {

        interface ZoneConfig extends Builder.ZoneConfig<GreTunnelZone> {

        }
    }
}
