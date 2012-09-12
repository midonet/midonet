/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman.state.zkManagers;

import com.midokura.midolman.state.*;
import com.midokura.midolman.util.JSONSerializer;
import com.midokura.midonet.cluster.data.TunnelZone;
import com.midokura.midonet.cluster.data.zones.*;
import com.midokura.util.functors.CollectionFunctors;
import com.midokura.util.functors.Functor;
import org.apache.zookeeper.Op;
import org.codehaus.jackson.annotate.JsonSubTypes;
import org.codehaus.jackson.annotate.JsonTypeInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class TunnelZoneZkManager extends ZkManager {

    private final static Logger log =
        LoggerFactory.getLogger(TunnelZoneZkManager.class);

    public TunnelZoneZkManager(Directory zk, String basePath) {
        super(zk, basePath);
        serializer = new ZkConfigSerializer(
            new JSONSerializer()
                .useMixin(TunnelZone.Data.class,
                          ZoneDataMixin.class)
                .useMixin(TunnelZone.HostConfig.Data.class,
                          ZoneHostDataMixin.class)
        );
    }

    public Set<UUID> getZoneIds() throws StateAccessException {

        String path = pathManager.getTunnelZonesPath();
        Set<String> zoneIdSet = getChildren(path);
        Set<UUID> zoneIds = new HashSet<UUID>(zoneIdSet.size());
        for (String zoneId : zoneIdSet) {
            zoneIds.add(UUID.fromString(zoneId));
        }

        return zoneIds;
    }

    public boolean exists(UUID zoneId) throws StateAccessException {
        return exists(pathManager.getTunnelZonePath(zoneId));
    }

    public TunnelZone<?, ?> getZone(UUID zoneId, Directory.TypedWatcher watcher)
        throws StateAccessException {

        String tunnelZonePath = pathManager.getTunnelZonePath(zoneId);
        if (!exists(tunnelZonePath)) {
            return null;
        }

        byte[] bytes = get(tunnelZonePath, watcher);
        TunnelZone.Data data =
            serializer.deserialize(bytes, TunnelZone.Data.class);

        if (data instanceof GreTunnelZone.Data) {
            GreTunnelZone.Data greData = (GreTunnelZone.Data) data;
            return new GreTunnelZone(zoneId, greData);
        }

        if (data instanceof IpsecTunnelZone.Data) {
            IpsecTunnelZone.Data ipsecData = (IpsecTunnelZone.Data) data;
            return new IpsecTunnelZone(zoneId, ipsecData);
        }

        if (data instanceof CapwapTunnelZone.Data) {
            CapwapTunnelZone.Data capwapData = (CapwapTunnelZone.Data) data;
            return new CapwapTunnelZone(zoneId, capwapData);
        }

        return null;
    }

    public boolean membershipExists(UUID zoneId, UUID hostId)
            throws StateAccessException {
        return exists(pathManager.getTunnelZoneMembershipPath(zoneId, hostId));
    }

    public TunnelZone.HostConfig<?, ?> getZoneMembership(UUID zoneId, UUID hostId, Directory.TypedWatcher watcher)
        throws StateAccessException {

        String zoneMembershipPath =
                pathManager.getTunnelZoneMembershipPath(zoneId, hostId);
        if (!exists(zoneMembershipPath)) {
            return null;
        }

        byte[] bytes = get(zoneMembershipPath, watcher);

        TunnelZone.HostConfig.Data data =
            serializer.deserialize(bytes, TunnelZone.HostConfig.Data.class);

        if (data instanceof GreTunnelZoneHost.Data) {
            return new GreTunnelZoneHost(
                hostId,
                (GreTunnelZoneHost.Data) data);
        }

        if (data instanceof IpsecTunnelZoneHost.Data) {
            return new IpsecTunnelZoneHost(
                hostId,
                (IpsecTunnelZoneHost.Data) data);
        }

        if (data instanceof CapwapTunnelZoneHost.Data) {
            return new CapwapTunnelZoneHost(
                hostId,
                (CapwapTunnelZoneHost.Data) data);
        }

        return null;
    }

    public Set<UUID> getZoneMemberships(UUID zoneId, Directory.TypedWatcher watcher)
        throws StateAccessException {

        String zoneMembershipsPath =
            pathManager.getTunnelZoneMembershipsPath(zoneId);

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

    public void updateZone(TunnelZone<?, ?> zone) throws StateAccessException {

        List<Op> updateMulti = new ArrayList<Op>();

        TunnelZone oldZone = getZone(zone.getId(), null);
        UUID id = UUID.fromString(oldZone.getId().toString());

        // Allow updating of the name
        oldZone.setName(zone.getName());

        updateMulti.add(
                getSetDataOp(
                        pathManager.getTunnelZonePath(id),
                        serializer.serialize(oldZone.getData())
                )
        );

        multi(updateMulti);

    }

    public UUID createZone(TunnelZone<?, ?> zone, Directory.TypedWatcher watcher)
        throws StateAccessException {

        log.debug("Creating availability zone {}", zone);
        List<Op> createMulti = new ArrayList<Op>();

        UUID zoneId = zone.getId();

        if (zoneId == null) {
            zoneId = UUID.randomUUID();
        }

        if (!exists(pathManager.getTunnelZonesPath())) {
            createMulti.add(
                getPersistentCreateOp(
                    pathManager.getTunnelZonesPath(), null
                )
            );
        }

        createMulti.add(
            getPersistentCreateOp(
                pathManager.getTunnelZonePath(zoneId),
                serializer.serialize(zone.getData())
            )
        );

        createMulti.add(
            getPersistentCreateOp(
                pathManager.getTunnelZoneMembershipsPath(zoneId),
                null
            )
        );

        multi(createMulti);
        zone.setId(zoneId);
        return zoneId;
    }

    public UUID addMembership(UUID zoneId, TunnelZone.HostConfig<?, ?> hostConfig)
        throws StateAccessException {
        log.debug("Adding to availability zone {} <- {}", zoneId, hostConfig);

        String zonePath = pathManager.getTunnelZonePath(zoneId);
        if (!exists(zonePath))
            return null;

        List<Op> ops = new ArrayList<Op>();

        String membershipsPath = pathManager.getTunnelZoneMembershipsPath(
            zoneId);
        if ( !exists(membershipsPath)) {
            ops.add(
                getPersistentCreateOp(membershipsPath, null)
            );
        }

        String membershipPath = pathManager.getTunnelZoneMembershipPath(zoneId,
                                                                        hostConfig
                                                                            .getId());
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

        String zonePath = pathManager.getTunnelZonePath(uuid);

        if (exists(zonePath)) {
            multi(getRecursiveDeleteOps(zonePath));
        }
    }

    public void delMembership(UUID zoneId, UUID membershipId)
        throws StateAccessException {
        try {
            delete(pathManager.getTunnelZoneMembershipPath(zoneId, membershipId));
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
                value = GreTunnelZone.Data.class,
                name = "gre"),
            @JsonSubTypes.Type(
                value = IpsecTunnelZone.Data.class,
                name = "ipsec"),
            @JsonSubTypes.Type(
                value = CapwapTunnelZone.Data.class,
                name = "capwap")
        }
    )
    abstract static class ZoneDataMixin {


    }

    @JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.PROPERTY, property = "@type")
    @JsonSubTypes(
        {
            @JsonSubTypes.Type(
                value = GreTunnelZoneHost.Data.class,
                name = "gre"),
            @JsonSubTypes.Type(
                value = IpsecTunnelZoneHost.Data.class,
                name = "ipsec"),
            @JsonSubTypes.Type(
                value = CapwapTunnelZoneHost.Data.class,
                name = "capwap")
        }
    )
    abstract static class ZoneHostDataMixin {
    }
}
