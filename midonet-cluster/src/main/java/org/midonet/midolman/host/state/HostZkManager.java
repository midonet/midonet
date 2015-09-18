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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.google.inject.Inject;

import org.apache.zookeeper.Op;
import org.apache.zookeeper.Watcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.cluster.WatchableZkManager;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.serialization.Serializer;
import org.midonet.midolman.state.AbstractZkManager;
import org.midonet.midolman.state.Directory;
import org.midonet.midolman.state.PathBuilder;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.ZkManager;

/**
 * Wrapper class over a Directory that handled setting and reading data related
 * to hosts and interfaces associated with the hosts.
 */
public class HostZkManager
        extends AbstractZkManager<UUID, HostDirectory.Metadata>
        implements WatchableZkManager<UUID, HostDirectory.Metadata> {

    private final static Logger log =
        LoggerFactory.getLogger(HostZkManager.class);

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

    public boolean hasPortBindings(UUID id) throws StateAccessException {
        Set<String> portChildren =
            zk.getChildren(paths.getHostVrnPortMappingsPath(id));

        return !portChildren.isEmpty();
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

}
