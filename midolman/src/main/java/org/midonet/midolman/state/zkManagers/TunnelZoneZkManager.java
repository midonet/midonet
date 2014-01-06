/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.midolman.state.zkManagers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.apache.zookeeper.Op;
import org.midonet.midolman.state.TunnelZoneExistsException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.midolman.serialization.Serializer;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.AbstractZkManager;
import org.midonet.midolman.state.Directory;
import org.midonet.midolman.state.NoStatePathException;
import org.midonet.midolman.state.PathBuilder;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.StatePathExistsException;
import org.midonet.midolman.state.ZkManager;
import org.midonet.cluster.data.TunnelZone;
import org.midonet.cluster.data.zones.CapwapTunnelZone;
import org.midonet.cluster.data.zones.CapwapTunnelZoneHost;
import org.midonet.cluster.data.zones.GreTunnelZone;
import org.midonet.cluster.data.zones.GreTunnelZoneHost;
import org.midonet.cluster.data.zones.IpsecTunnelZone;
import org.midonet.cluster.data.zones.IpsecTunnelZoneHost;
import org.midonet.util.functors.CollectionFunctors;
import org.midonet.util.functors.Functor;

public class TunnelZoneZkManager extends AbstractZkManager {

    private final static Logger log =
        LoggerFactory.getLogger(TunnelZoneZkManager.class);

    /**
     * Initializes a TunnelZkManager object with a ZooKeeper client and the root
     * path of the ZooKeeper directory.
     *
     * @param zk
     *         Zk data access class
     * @param paths
     *         PathBuilder class to construct ZK paths
     * @param serializer
     *         ZK data serialization class
     */
    public TunnelZoneZkManager(ZkManager zk, PathBuilder paths,
                               Serializer serializer) {
        super(zk, paths, serializer);
    }

    public TunnelZoneZkManager(Directory dir, String basePath,
                               Serializer serializer) {
        this(new ZkManager(dir), new PathBuilder(basePath), serializer);
    }

    public Set<UUID> getZoneIds() throws StateAccessException {

        String path = paths.getTunnelZonesPath();
        Set<String> zoneIdSet = zk.getChildren(path);
        Set<UUID> zoneIds = new HashSet<UUID>(zoneIdSet.size());
        for (String zoneId : zoneIdSet) {
            zoneIds.add(UUID.fromString(zoneId));
        }

        return zoneIds;
    }

    public boolean exists(UUID zoneId) throws StateAccessException {
        return zk.exists(paths.getTunnelZonePath(zoneId));
    }

    public TunnelZone<?, ?> getZone(UUID zoneId, Directory.TypedWatcher watcher)
            throws StateAccessException, SerializationException {

        String tunnelZonePath = paths.getTunnelZonePath(zoneId);
        if (!zk.exists(tunnelZonePath)) {
            return null;
        }

        byte[] bytes = zk.get(tunnelZonePath, watcher);
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
        return zk.exists(paths.getTunnelZoneMembershipPath(zoneId, hostId));
    }

    public TunnelZone.HostConfig<?, ?> getZoneMembership(UUID zoneId, UUID hostId, Directory.TypedWatcher watcher)
            throws StateAccessException, SerializationException {

        String zoneMembershipPath =
                paths.getTunnelZoneMembershipPath(zoneId, hostId);
        if (!zk.exists(zoneMembershipPath)) {
            return null;
        }

        byte[] bytes = zk.get(zoneMembershipPath, watcher);

        TunnelZone.HostConfig.Data data =
                serializer.deserialize(bytes,
                        TunnelZone.HostConfig.Data.class);

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
            paths.getTunnelZoneMembershipsPath(zoneId);

        if (!zk.exists(zoneMembershipsPath))
            return Collections.emptySet();

        return CollectionFunctors.map(
                zk.getChildren(zoneMembershipsPath, watcher),
            new Functor<String, UUID>() {
                @Override
                public UUID apply(String arg0) {
                    return UUID.fromString(arg0);
                }
            }, new HashSet<UUID>()
        );
    }

    public void updateZone(TunnelZone<?, ?> zone) throws StateAccessException,
            SerializationException {

        List<Op> updateMulti = new ArrayList<Op>();

        TunnelZone oldZone = getZone(zone.getId(), null);
        UUID id = UUID.fromString(oldZone.getId().toString());

        // Allow updating of the name
        oldZone.setName(zone.getName());

        updateMulti.add(
                zk.getSetDataOp(
                        paths.getTunnelZonePath(id),
                        serializer.serialize(oldZone.getData())
                )
        );

        zk.multi(updateMulti);

    }

    /**
     * Creates a new tunnel zone, but validates that there is not one already
     * with the same name. This same check is done in the API but added here
     * for extra safety.
     */
    public UUID createZone(TunnelZone<?, ?> zone, Directory.TypedWatcher watcher)
            throws StateAccessException, SerializationException {

        log.debug("Creating availability zone {}", zone);
        List<Op> createMulti = new ArrayList<Op>();

        UUID zoneId = zone.getId();

        if (zoneId == null) {
            zoneId = UUID.randomUUID();
        }

        for (UUID tzId : this.getZoneIds()) {
            TunnelZone tz = this.getZone(tzId, null);
            if (tz.getType().equals(zone.getType()) &&
                tz.getName().equalsIgnoreCase(zone.getName())) {
                throw new StatePathExistsException(
                    "There is already a tunnel zone with the same type and" +
                    "name, its id: " + tz.getId(), (String)null);
            }
        }

        createMulti.add(
                zk.getPersistentCreateOp(
                paths.getTunnelZonePath(zoneId),
                    serializer.serialize(zone.getData())
            )
        );

        createMulti.add(
                zk.getPersistentCreateOp(
                paths.getTunnelZoneMembershipsPath(zoneId),
                null
            )
        );

        zk.multi(createMulti);
        zone.setId(zoneId);
        return zoneId;
    }

    public UUID addMembership(UUID zoneId, TunnelZone.HostConfig<?, ?> hostConfig)
            throws StateAccessException, SerializationException {
        log.debug("Adding to tunnel zone {} <- {}", zoneId, hostConfig);
        String zonePath = paths.getTunnelZonePath(zoneId);
        if (!zk.exists(zonePath))
            return null;

        List<Op> ops = new ArrayList<Op>();

        String membershipsPath = paths.getTunnelZoneMembershipsPath(
            zoneId);
        if ( !zk.exists(membershipsPath)) {
            ops.add(
                    zk.getPersistentCreateOp(membershipsPath, null)
            );
        }

        String membershipPath = paths.getTunnelZoneMembershipPath(
                zoneId, hostConfig.getId());

        if (zk.exists(membershipPath))
            throw new StatePathExistsException(null, membershipPath);

        ops.add(
                zk.getPersistentCreateOp(membershipPath,
                    serializer.serialize(hostConfig.getData()))
        );

        String hostInZonePath =
            paths.getHostTunnelZonePath(hostConfig.getId(), zoneId);

        if (!zk.exists(hostInZonePath)) {
            ops.add(
                    zk. getPersistentCreateOp(hostInZonePath, null)
            );
        }

        zk.multi(ops);
        return hostConfig.getId();
    }

    public void deleteZone(UUID uuid) throws StateAccessException {

        List<Op> ops = new ArrayList<Op>();
        for (UUID membershipId : this.getZoneMemberships(uuid, null)) {
            ops.add(
                zk.getDeleteOp(paths.getHostTunnelZonePath(membershipId, uuid))
            );
        }

        String zonePath = paths.getTunnelZonePath(uuid);
        if (zk.exists(zonePath)) {
            ops.addAll(zk.getRecursiveDeleteOps(zonePath));
        }
        zk.multi(ops);
    }

    public void delMembership(UUID zoneId, UUID membershipId)
        throws StateAccessException {
        try {
            List<Op> ops = new ArrayList<Op>();

            ops.add(
                    zk.getDeleteOp(paths.getTunnelZoneMembershipPath(zoneId,
                            membershipId))
            );

            ops.add(
                    zk.getDeleteOp(paths.getHostTunnelZonePath(membershipId,
                            zoneId))
            );

            zk.multi(ops);
        } catch (NoStatePathException e) {
            // silently fail if the node was already deleted.
        }
    }
}
