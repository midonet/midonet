/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman.state.zkManagers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.apache.zookeeper.Op;
import org.codehaus.jackson.annotate.JsonProperty;
import org.codehaus.jackson.annotate.JsonSubTypes;
import org.codehaus.jackson.annotate.JsonTypeInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.state.Directory;
import com.midokura.midolman.state.NoStatePathException;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midolman.state.ZkConfigSerializer;
import com.midokura.midolman.state.ZkManager;
import com.midokura.midolman.util.JSONSerializer;
import com.midokura.midonet.cluster.data.AvailabilityZone;
import com.midokura.midonet.cluster.data.zones.CapwapAvailabilityZone;
import com.midokura.midonet.cluster.data.zones.CapwapAvailabilityZoneHost;
import com.midokura.midonet.cluster.data.zones.GreAvailabilityZone;
import com.midokura.midonet.cluster.data.zones.GreAvailabilityZoneHost;
import com.midokura.midonet.cluster.data.zones.IpsecAvailabilityZone;
import com.midokura.midonet.cluster.data.zones.IpsecAvailabilityZoneHost;
import com.midokura.util.functors.CollectionFunctors;
import com.midokura.util.functors.Functor;

public class AvailabilityZoneZkManager extends ZkManager {

    private final static Logger log =
        LoggerFactory.getLogger(AvailabilityZoneZkManager.class);

    public AvailabilityZoneZkManager(Directory zk, String basePath) {
        super(zk, basePath);
        serializer = new ZkConfigSerializer(
            new JSONSerializer()
                .useMixin(AvailabilityZone.Data.class,
                          ZoneDataMixin.class)
                .useMixin(AvailabilityZone.HostConfig.Data.class,
                          ZoneHostDataMixin.class)
        );
    }

    public AvailabilityZone<?, ?> getZone(UUID zoneId, Directory.TypedWatcher watcher)
        throws StateAccessException {

        byte[] bytes = get(pathManager.getAvailabilityZonePath(zoneId),
                           watcher);
        AvailabilityZone.Data data =
            serializer.deserialize(bytes, AvailabilityZone.Data.class);

        if (data instanceof GreAvailabilityZone.Data) {
            GreAvailabilityZone.Data greData = (GreAvailabilityZone.Data) data;
            return new GreAvailabilityZone(zoneId, greData);
        }

        if (data instanceof IpsecAvailabilityZone.Data) {
            IpsecAvailabilityZone.Data ipsecData = (IpsecAvailabilityZone.Data) data;
            return new IpsecAvailabilityZone(zoneId, ipsecData);
        }

        if (data instanceof CapwapAvailabilityZone.Data) {
            CapwapAvailabilityZone.Data capwapData = (CapwapAvailabilityZone.Data) data;
            return new CapwapAvailabilityZone(zoneId, capwapData);
        }

        return null;
    }

    public AvailabilityZone.HostConfig<?, ?> getZoneMembership(UUID zoneId, UUID hostId, Directory.TypedWatcher watcher)
        throws StateAccessException {

        byte[] bytes =
            get(pathManager.getAvailabilityZoneMembershipPath(zoneId, hostId),
                watcher);

        AvailabilityZone.HostConfig.Data data =
            serializer.deserialize(bytes, AvailabilityZone.HostConfig.Data.class);

        if (data instanceof GreAvailabilityZoneHost.Data) {
            return new GreAvailabilityZoneHost(
                hostId,
                (GreAvailabilityZoneHost.Data) data);
        }

        if (data instanceof IpsecAvailabilityZoneHost.Data) {
            return new IpsecAvailabilityZoneHost(
                hostId,
                (IpsecAvailabilityZoneHost.Data) data);
        }

        if (data instanceof CapwapAvailabilityZoneHost.Data) {
            return new CapwapAvailabilityZoneHost(
                hostId,
                (CapwapAvailabilityZoneHost.Data) data);
        }

        return null;
    }

    public Set<UUID> getZoneMemberships(UUID zoneId, Directory.TypedWatcher watcher)
        throws StateAccessException {

        String zoneMembershipsPath =
            pathManager.getAvailabilityZoneMembershipsPath(zoneId);

        if (!exists(zoneMembershipsPath))
            return Collections.emptySet();

        return CollectionFunctors.map(
            getChildren(zoneMembershipsPath, watcher),
            new Functor<String, UUID>() {
                @Override
                public UUID apply(String arg0) {
                    return UUID.fromString(arg0);
                }
            }, new HashSet<UUID>()
        );
    }

    public UUID createZone(AvailabilityZone<?, ?> zone, Directory.TypedWatcher watcher)
        throws StateAccessException {

        log.debug("Creating availability zone {}", zone);
        List<Op> createMulti = new ArrayList<Op>();

        UUID zoneId = zone.getId();

        if (zoneId == null) {
            zoneId = UUID.randomUUID();
        }

        if (!exists(pathManager.getAvailabilityZonesPath())) {
            createMulti.add(
                getPersistentCreateOp(
                    pathManager.getAvailabilityZonesPath(), null
                )
            );
        }

        createMulti.add(
            getPersistentCreateOp(
                pathManager.getAvailabilityZonePath(zoneId),
                serializer.serialize(zone.getData())
            )
        );

        createMulti.add(
            getPersistentCreateOp(
                pathManager.getAvailabilityZoneMembershipsPath(zoneId),
                null
            )
        );

        multi(createMulti);
        zone.setId(zoneId);
        return zoneId;
    }

    public UUID addMembership(UUID zoneId, AvailabilityZone.HostConfig<?, ?> hostConfig)
        throws StateAccessException {
        log.debug("Adding to availability zone {} <- {}", zoneId, hostConfig);

        String zonePath = pathManager.getAvailabilityZonePath(zoneId);
        if (!exists(zonePath))
            return null;

        List<Op> ops = new ArrayList<Op>();

        String membershipsPath = pathManager.getAvailabilityZoneMembershipsPath(
            zoneId);
        if ( !exists(membershipsPath)) {
            ops.add(
                getPersistentCreateOp(membershipsPath, null)
            );
        }

        String membershipPath = pathManager.getAvailabilityZoneMembershipPath(zoneId, hostConfig.getId());
        if (exists(membershipPath)) {
            ops.add(getDeleteOp(membershipPath));
        }

        ops.add(
            getPersistentCreateOp(membershipPath,
                                  serializer.serialize(hostConfig.getData()))
        );

        multi(ops);

        return hostConfig.getId();
    }

    public void deleteZone(UUID uuid) throws StateAccessException {

        String zonePath = pathManager.getAvailabilityZonePath(uuid);

        if (!exists(zonePath)) {
            multi(getRecursiveDeleteOps(zonePath));
        }
    }

    public void delMembership(UUID zoneId, UUID membershipId)
        throws StateAccessException {
        try {
            delete(pathManager.getAvailabilityZoneMembershipPath(zoneId, membershipId));
        } catch (NoStatePathException e) {
            // silently fail if the node was already deleted.
        }
    }

    @JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.PROPERTY, property = "@type")
    @JsonSubTypes(
        {
            @JsonSubTypes.Type(
                value = GreAvailabilityZone.Data.class,
                name = "gre"),
            @JsonSubTypes.Type(
                value = IpsecAvailabilityZone.Data.class,
                name = "ipsec"),
            @JsonSubTypes.Type(
                value = CapwapAvailabilityZone.Data.class,
                name = "capwap")
        }
    )
    abstract static class ZoneDataMixin {

        @JsonProperty
        String name;
    }

    @JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.PROPERTY, property = "@type")
    @JsonSubTypes(
        {
            @JsonSubTypes.Type(
                value = GreAvailabilityZoneHost.Data.class,
                name = "gre"),
            @JsonSubTypes.Type(
                value = IpsecAvailabilityZoneHost.Data.class,
                name = "ipsec"),
            @JsonSubTypes.Type(
                value = CapwapAvailabilityZoneHost.Data.class,
                name = "capwap")
        }
    )
    abstract static class ZoneHostDataMixin {
    }
}
