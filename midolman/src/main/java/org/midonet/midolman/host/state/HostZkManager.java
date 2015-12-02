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
package org.midonet.midolman.host.state;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.google.common.annotations.VisibleForTesting;
import com.google.inject.Inject;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.Watcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.cluster.WatchableZkManager;
import org.midonet.cluster.data.Converter;
import org.midonet.cluster.data.Port;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.serialization.Serializer;
import org.midonet.midolman.state.AbstractZkManager;
import org.midonet.midolman.state.Directory;
import org.midonet.midolman.state.NoStatePathException;
import org.midonet.midolman.state.PathBuilder;
import org.midonet.midolman.state.PortConfig;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.ZkManager;
import org.midonet.midolman.state.zkManagers.PortZkManager;
import org.midonet.midolman.version.DataWriteVersion;

/**
 * Wrapper class over a Directory that handled setting and reading data related
 * to hosts and interfaces associated with the hosts.
 */
public class HostZkManager
        extends AbstractZkManager<UUID, HostDirectory.Metadata>
        implements WatchableZkManager<UUID, HostDirectory.Metadata> {

    private final static Logger log =
        LoggerFactory.getLogger(HostZkManager.class);

    private final PortZkManager portZkManager;

    /**
     * Initializes a HostZkManager object with a ZooKeeper client
     * and the root path of the ZooKeeper directory.
     *
     * @param zk
     *         Zk data access class
     * @param paths
     *         PathBuilder class to construct ZK paths
     * @param serializer
     *         ZK data serialization class
     */
    @Inject
    public HostZkManager(ZkManager zk, PathBuilder paths,
                         Serializer serializer) {
        super(zk, paths, serializer);
        this.portZkManager = new PortZkManager(zk, paths, serializer);
    }

    @Override
    protected String getConfigPath(UUID id) {
        return paths.getHostPath(id);
    }

    @Override
    protected Class<HostDirectory.Metadata> getConfigClass() {
        return HostDirectory.Metadata.class;
    }

    public void createHost(UUID hostId, HostDirectory.Metadata metadata)
            throws StateAccessException, SerializationException {
        zk.multi(prepareCreate(hostId, metadata));
        log.info("Host {} registered to NSDB", hostId);
    }

    public List<Op> prepareCreate(UUID hostId, HostDirectory.Metadata metadata)
            throws StateAccessException, SerializationException {

        log.debug("Creating host folders for hostId {}", hostId);

        List<Op> ops = new ArrayList<>(metadata.getTunnelZones().size() + 7);
        try {
            ops.add(simpleCreateOp(hostId, metadata));
        } catch (SerializationException e) {
            throw new SerializationException(
                    "Could not serialize host metadata for id: " + hostId,
                    e, HostDirectory.Metadata.class);
        }

        ops.addAll(zk.getPersistentCreateOps(
                paths.getHostInterfacesPath(hostId),
                paths.getHostVrnMappingsPath(hostId),
                paths.getHostVrnPortMappingsPath(hostId),
                paths.getHostTunnelZonesPath(hostId)));

        for (UUID uuid : metadata.getTunnelZones()) {
            ops.add(zk.getPersistentCreateOp(
                    paths.getHostTunnelZonePath(hostId, uuid), null));
        }

        return ops;
    }

    public void makeAlive(UUID hostId) throws StateAccessException {
        String path = paths.getHostPath(hostId) + "/alive";
        log.debug("Making host {} alive at {}", hostId, path);
        zk.ensureEphemeral(path, new byte[0]);
    }

    public void makeNotAlive(UUID hostId) throws StateAccessException {
        String path = paths.getHostPath(hostId) + "/alive";
        zk.deleteEphemeral(path);
    }

    /**
     * Set this host's version in ZK. This value will be read by
     * the upgrade coordinator to see which version everyone is on.
     */
    public void setHostVersion(UUID hostId) throws StateAccessException {
        String p = paths.getHostVersionPath(hostId, DataWriteVersion.CURRENT);
        zk.ensureEphemeral(p, new byte[0]);
    }

    public void updateMetadata(UUID hostId, HostDirectory.Metadata metadata)
            throws StateAccessException, SerializationException {
        if (metadata != null) {
            try {
                zk.multi(Arrays.asList(simpleUpdateOp(hostId, metadata)));
            } catch (SerializationException e) {
                throw new SerializationException(
                    "Could not serialize host metadata for id: " + hostId,
                    e, HostDirectory.Metadata.class);
            }
        }
    }

    public boolean isAlive(UUID id) throws StateAccessException {
        return zk.exists(paths.getHostPath(id) + "/alive");
    }

    public boolean isAlive(UUID id, Runnable watcher) throws StateAccessException {
        return zk.exists(paths.getHostPath(id) + "/alive", watcher);
    }

    public boolean isAlive(UUID id, Watcher watcher) throws StateAccessException {
        return zk.exists(paths.getHostPath(id) + "/alive", watcher);
    }

    /**
     * Set the flooding proxy weight value.
     *
     * The value is an Integer for consistency with getFloodingProxyWeight,
     * which may return null if the value has not been initialized.
     * Nevertheless, the input value of setFloodingProxy cannot be null.
     * @param hostId is the host id
     * @param weight is a non-null, non-negative integer value.
     */
    public void setFloodingProxyWeight(UUID hostId, int weight)
        throws StateAccessException, SerializationException {
        String path = paths.getHostFloodingProxyWeightPath(hostId);
        byte[] value = serializer.serialize(weight);
        try {
            zk.update(path, value);
        } catch (NoStatePathException e) {
            /*
             * if the node exists again at this point, it means that the
             * value that was going to be written was overwritten by someone
             * else... So, we do not care about our value anymore.
             */
            zk.addPersistent_safe(path, value);
        }
    }

    /**
     * Get the flooding proxy weight value for the host.
     *
     * The returned value is null if the value was not present/initialized in
     * zk; it is the responsibility of the caller to convert that into the
     * required default value (or take any other necessary actions).
     * @param hostId is the host id
     * @return the proxy weight value or null if it was not initialized.
     */
    public Integer getFloodingProxyWeight(UUID hostId)
        throws StateAccessException, SerializationException {
        String path = paths.getHostFloodingProxyWeightPath(hostId);
        String hostPath = paths.getHostPath(hostId);
        if (zk.exists(hostPath) && !zk.exists(path)) {
            return null;
        } else {
            byte[] data = zk.get(path);
            return serializer.deserialize(data, int.class);
        }
    }

    /**
     * Gets the flooding proxy weight value for the host, and sets up
     * a watcher on the flooding proxy weight path. The method returns null, and
     * does not set any watcher if the host node does not exist.
     *
     * @param hostId The host identifier.
     * @param watcher The watcher.
     * @return The flooding proxy weight value or null if it was not
     * initialized.
     */
    public Integer getFloodingProxyWeight(UUID hostId, Watcher watcher)
        throws StateAccessException, SerializationException {
        String path = paths.getHostFloodingProxyWeightPath(hostId);
        String hostPath = paths.getHostPath(hostId);

        if (!zk.exists(hostPath))
            return null;

        if (zk.exists(path, watcher)) {
            byte[] data = zk.get(path);
            return serializer.deserialize(data, int.class);
        } else {
            return null;
        }
    }

    public boolean hasPortBindings(UUID id) throws StateAccessException {
        Set<String> portChildren =
            zk.getChildren(paths.getHostVrnPortMappingsPath(id));

        return !portChildren.isEmpty();
    }

    public void deleteHost(UUID id) throws StateAccessException {
        zk.multi(prepareDelete(id));
    }

    public List<Op> prepareDelete(UUID id) throws StateAccessException {

        List<Op> ops = new ArrayList<>();

        Collection<UUID> tunnelZones = getTunnelZoneIds(id, null);
        for (UUID zoneId : tunnelZones) {
            ops.addAll(zk.getDeleteOps(
                    paths.getHostTunnelZonePath(id, zoneId),
                    paths.getTunnelZoneMembershipPath(zoneId, id)));
        }

        if (zk.exists(paths.getHostFloodingProxyWeightPath(id))) {
            ops.addAll(zk.getDeleteOps(
                paths.getHostFloodingProxyWeightPath(id)));
        }

        /*
         * The 'command' and 'error' path do not get created under host
         * as of v1.7, but it is possible that it exists anyway if this
         * deployment has gone through an upgrade. Delete the 'command'
         * path if it exists.
         */
        String commandPath = paths.getHostCommandsPath(id);
        if (zk.exists(commandPath)) {
            ops.add(zk.getDeleteOp(commandPath));
        }
        String errorPath = paths.getHostCommandErrorLogsPath(id);
        if (zk.exists(errorPath)) {
            ops.add(zk.getDeleteOp(errorPath));
        }

        ops.addAll(zk.getDeleteOps(
                paths.getHostInterfacesPath(id),
                paths.getHostVrnPortMappingsPath(id),
                paths.getHostVrnDatapathMappingPath(id),
                paths.getHostVrnMappingsPath(id),
                paths.getHostTunnelZonesPath(id),
                paths.getHostPath(id)));

        return ops;
    }

    public List<UUID> getHostIds() throws StateAccessException {
        return getHostIds(null);
    }

    public List<UUID> getHostIds(Directory.TypedWatcher watcher)
            throws StateAccessException {
        return getUuidList(paths.getHostsPath(), watcher);
    }

    public boolean existsInterface(UUID hostId, String interfaceName)
            throws StateAccessException {
        return zk.exists(paths.getHostInterfacePath(hostId,
                                                       interfaceName));
    }

    public void createInterface(UUID hostId,
                                HostDirectory.Interface anInterface)
            throws StateAccessException, IOException, SerializationException {

        List<Op> ops = new ArrayList<>();

        if (!zk.exists(paths.getHostInterfacesPath(hostId))) {
            ops.add(zk.getPersistentCreateOp(
                paths.getHostInterfacesPath(hostId), null));
        }
        ops.add(zk.getEphemeralCreateOp(
            paths.getHostInterfacePath(hostId, anInterface.getName()),
            serializer.serialize(anInterface)));

        zk.multi(ops);
    }

    public HostDirectory.Interface getInterfaceData(UUID hostId,
                                                    String name)
            throws StateAccessException, SerializationException {

        try {
            byte[] data = zk.get(
                paths.getHostInterfacePath(hostId, name));

            return serializer.deserialize(data, HostDirectory.Interface.class);
        } catch (SerializationException e) {
            throw new SerializationException(
                "Could not deserialize host interface metadata for id: " +
                    hostId + " / " + name,
                e, HostDirectory.Metadata.class);
        }
    }

    /**
     * Will return the collection of names for all the interfaces presently
     * described under the provided host entry.
     *
     * @param hostId the host id for which we want the interfaces
     * @return the collection of interface names
     * @throws StateAccessException if we weren't able to properly communicate
     *                              with the datastore.
     */
    public Set<String> getInterfaces(UUID hostId) throws StateAccessException {

        String path = paths.getHostInterfacesPath(hostId);
        if (!zk.exists(path)) {
            return Collections.emptySet();
        }

        return zk.getChildren(path);
    }

    public void updateHostInterfaces(UUID hostId,
                                     List<HostDirectory.Interface>
                                         currentInterfaces,
                                     Set<String> obsoleteInterfaces)
        throws StateAccessException {

        List<Op> updateInterfacesOperation = new ArrayList<>();

        for (String obsoleteInterface : obsoleteInterfaces) {
            updateInterfacesOperation.add(
                    zk.getDeleteOp(
                    paths.getHostInterfacePath(hostId,
                                                     obsoleteInterface)));
        }

        for (HostDirectory.Interface hostInterface : currentInterfaces) {
            try {

                String hostInterfacePath =
                    paths.getHostInterfacePath(hostId,
                                                     hostInterface.getName());
                byte[] serializedData = serializer.serialize(hostInterface);

                Op hostInterfaceOp;
                if (zk.exists(hostInterfacePath)) {
                    hostInterfaceOp =
                            zk.getSetDataOp(hostInterfacePath, serializedData);
                } else {
                    hostInterfaceOp =
                            zk.getEphemeralCreateOp(hostInterfacePath,
                                    serializedData);
                }

                updateInterfacesOperation.add(hostInterfaceOp);

            } catch (SerializationException ex) {
                log.warn("Could not serialize interface data {}.",
                        hostInterface, ex);
            }
        }

        if (!updateInterfacesOperation.isEmpty()) {
            zk.multi(updateInterfacesOperation);
        }
    }

    public String getVirtualDatapathMapping(UUID hostIdentifier, Runnable watcher)
            throws StateAccessException, SerializationException {

        String hostVrnDatapathMappingPath =
            paths.getHostVrnDatapathMappingPath(hostIdentifier);

        if (!zk.exists(hostVrnDatapathMappingPath)) {
            addVirtualDatapathMapping(hostIdentifier, "midonet");
            return "midonet";
        }

        return serializer.deserialize(
            zk.get(hostVrnDatapathMappingPath, watcher),
            String.class);
    }

    public boolean virtualPortMappingExists(UUID hostId, UUID portId)
            throws StateAccessException {
        return zk.exists(paths.getHostVrnPortMappingPath(hostId, portId));
    }

    public HostDirectory.VirtualPortMapping getVirtualPortMapping(
            UUID hostId, UUID portId) throws StateAccessException,
            SerializationException {
        String path = paths.getHostVrnPortMappingPath(hostId, portId);
        if (!zk.exists(path)) {
            return null;
        }

        return serializer.deserialize(zk.get(path),
                HostDirectory.VirtualPortMapping.class);
    }

    public Set<HostDirectory.VirtualPortMapping> getVirtualPortMappings(
            UUID hostId, Directory.TypedWatcher watcher)
            throws StateAccessException, SerializationException {

        String mappingsPaths = paths.getHostVrnPortMappingsPath(hostId);
        Set<HostDirectory.VirtualPortMapping> portMappings = new HashSet<>();

        if (zk.exists(mappingsPaths)) {
            Collection<UUID> mappingIds = getUuidList(mappingsPaths, watcher);
            for (UUID mappingId : mappingIds) {
                portMappings.add(getVirtualPortMapping(hostId, mappingId));
            }
        }

        return portMappings;
    }

    @VisibleForTesting
    public void ensureHostPathExists(UUID hostIdentifier)
        throws StateAccessException {

        if (!zk.exists(paths.getHostPath(hostIdentifier))) {
            zk.add(paths.getHostPath(hostIdentifier), null /*data*/,
                   CreateMode.PERSISTENT);
        }
    }

    public void addVirtualDatapathMapping(UUID hostIdentifier,
                                          String datapathMapping)
            throws StateAccessException, SerializationException {

        List<Op> operations = new ArrayList<>();

        if (!zk.exists(paths.getHostPath(hostIdentifier)))
            operations.add(
                    zk.getPersistentCreateOp(paths.getHostPath(hostIdentifier),
                                      null));

        String virtualMappingPath =
            paths.getHostVrnMappingsPath(hostIdentifier);

        if (!zk.exists(virtualMappingPath)) {
            operations.add(zk.getPersistentCreateOp(virtualMappingPath, null));
        }

        String hostVrnDatapathMappingPath =
            paths.getHostVrnDatapathMappingPath(hostIdentifier);

        if (zk.exists(hostVrnDatapathMappingPath)) {
            operations.add(zk.getDeleteOp(hostVrnDatapathMappingPath));
        }

        operations.add(
                zk.getPersistentCreateOp(
                        hostVrnDatapathMappingPath,
                        serializer.serialize(datapathMapping)));

        zk.multi(operations);
    }

    public Port<?,?> addVirtualPortMapping(
            UUID hostIdentifier, HostDirectory.VirtualPortMapping portMapping)
            throws StateAccessException, SerializationException {

        // Make sure that the portID is valid
        UUID portId = portMapping.getVirtualPortId();
        if (!portZkManager.exists(portId)) {
            throw new IllegalArgumentException(
                "Port with ID " + portId + " does not exist");
        }

        List<Op> operations = new ArrayList<>();

        String hostPath = paths.getHostPath(hostIdentifier);
        if (!zk.exists(hostPath)) {
            operations.add(zk.getPersistentCreateOp(hostPath, null));
        }

        String virtualMappingPath =
            paths.getHostVrnMappingsPath(hostIdentifier);


        if (!zk.exists(virtualMappingPath)) {
            operations.add(zk.getPersistentCreateOp(virtualMappingPath, null));
        }

        String hostVrnPortMappingsPath =
            paths.getHostVrnPortMappingsPath(hostIdentifier);

        if (!zk.exists(hostVrnPortMappingsPath)) {
            operations.add(
                    zk.getPersistentCreateOp(hostVrnPortMappingsPath, null));
        }

        String hostVrnPortMappingPath =
            paths.getHostVrnPortMappingPath(hostIdentifier, portId);

        if (zk.exists(hostVrnPortMappingPath)) {
            operations.add(zk.getDeleteOp(hostVrnPortMappingPath));
        }

        operations.add(
                zk.getPersistentCreateOp(
                hostVrnPortMappingPath,
                    serializer.serialize(portMapping)));

        PortConfig port = portZkManager.get(portId);

        // Edits port config
        operations.add(getMapUpdatePortOp(port, portId, hostIdentifier,
                portMapping.getLocalDeviceName()));

        zk.multi(operations);

        Port<?,?> updatedPort = Converter.fromPortConfig(port);
        updatedPort.setId(portId);
        return updatedPort;
    }

    public void delVirtualPortMapping(UUID hostIdentifier, UUID portId)
            throws StateAccessException, SerializationException {

        String virtualMappingPath =
            paths.getHostVrnPortMappingPath(hostIdentifier, portId);

        List<Op> ops = new ArrayList<>();
        if (zk.exists(virtualMappingPath)) {

            ops.add(zk.getDeleteOp(virtualMappingPath));

            PortConfig port = portZkManager.get(portId);

            /*
             Update the port config (nullify the binding data) only if the port
             has a binding with "hostIdentifier".

             In the sequence of Nova compute's migration, unplug() on the
             original compute node gets called AFTER plug() on the target
             compute node gets executed.
             This check is to make sure that unplug() does not clear the port
             that has new hostId.
            */
            if (port.getHostId() != null &&
                    port.getHostId().equals(hostIdentifier)) {
                Op op = getMapUpdatePortOp(port, portId, null, null);
                if (op != null) {
                    ops.add(op);
                }
            }
            zk.multi(ops);
        }

    }

    private Op getMapUpdatePortOp(PortConfig port, UUID portId,
                                  UUID hostIdentifier,
                                  String localDeviceName)
            throws StateAccessException, SerializationException {

        port.setHostId(hostIdentifier);
        port.setInterfaceName(localDeviceName);

        String portPath = paths.getPortPath(portId);
        if (zk.exists(portPath)) {
            // Guard against data inconsistencies, MN-3937
            return zk.getSetDataOp(portPath, serializer.serialize(port));
        }
        return null;
    }

    public List<UUID> getTunnelZoneIds(UUID hostId,
                                      Directory.TypedWatcher watcher)
        throws StateAccessException {
        return getUuidList(paths.getHostTunnelZonesPath(hostId), watcher);
    }

    @Override
    public List<UUID> getAndWatchIdList(Runnable watcher)
        throws StateAccessException {
        return getUuidList(paths.getHostsPath(), watcher);
    }
}
