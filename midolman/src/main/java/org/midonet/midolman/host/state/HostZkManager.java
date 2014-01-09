/*
 * Copyright 2012 Midokura Europe SARL
 * Copyright 2013 Midokura Europe SARL
 */
package org.midonet.midolman.host.state;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.apache.zookeeper.Op;
import org.midonet.cluster.data.Converter;
import org.midonet.cluster.data.Port;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.serialization.Serializer;
import org.midonet.midolman.state.AbstractZkManager;
import org.midonet.midolman.state.Directory;
import org.midonet.midolman.state.PathBuilder;
import org.midonet.midolman.state.PortConfig;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.ZkManager;
import org.midonet.midolman.state.zkManagers.PortZkManager;
import org.midonet.midolman.version.DataWriteVersion;
import org.midonet.util.functors.CollectionFunctors;
import org.midonet.util.functors.Functor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static org.midonet.midolman.host.state.HostDirectory.Command;

/**
 * Wrapper class over a Directory that handled setting and reading data related
 * to hosts and interfaces associated with the hosts.
 */
public class HostZkManager extends AbstractZkManager {

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
    public HostZkManager(ZkManager zk, PathBuilder paths,
                         Serializer serializer) {
        super(zk, paths, serializer);
        this.portZkManager = new PortZkManager(zk, paths, serializer);
    }

    public HostZkManager(Directory dir, String basePath,
                         Serializer serializer) {
        this(new ZkManager(dir), new PathBuilder(basePath), serializer);
    }

    public HostDirectory.Metadata getHostMetadata(UUID id)
            throws StateAccessException, SerializationException {
        return getHostMetadata(id, null);
    }

    public HostDirectory.Metadata getHostMetadata(UUID id, Directory.TypedWatcher watcher)
            throws StateAccessException, SerializationException {
        byte[] data = zk.get(paths.getHostPath(id), watcher);
        HostDirectory.Metadata metadata;
        try {
            metadata = serializer.deserialize(data,
                    HostDirectory.Metadata.class);
        } catch (SerializationException e) {
            String dataAsString = new String(data);
            throw new SerializationException(
                "Could not deserialize host metadata for id: " + id +
                    " [" + dataAsString + "]",
                e,
                HostDirectory.Metadata.class);
        }
        return metadata;
    }

    public void createHost(UUID hostId, HostDirectory.Metadata metadata)
            throws StateAccessException, SerializationException {
        log.debug("Creating host folders for hostId {}", hostId);

        try {
            List<Op> ops = new ArrayList<Op>();
            ops.add(
                zk.getPersistentCreateOp(paths.getHostPath(hostId),
                        serializer.serialize(metadata)));
            ops.add(
                    zk.getPersistentCreateOp(paths.getHostInterfacesPath(hostId),
                                      null));
            ops.add(
                    zk.getPersistentCreateOp(paths.getHostCommandsPath(hostId),
                                      null));
            ops.add(
                    zk.getPersistentCreateOp(
                    paths.getHostCommandErrorLogsPath(hostId), null));

            ops.add(
                    zk.getPersistentCreateOp(
                    paths.getHostVrnMappingsPath(hostId), null));

            ops.add(
                    zk.getPersistentCreateOp(
                    paths.getHostVrnPortMappingsPath(hostId), null));

            ops.add(
                    zk.getPersistentCreateOp(
                    paths.getHostTunnelZonesPath(hostId), null));

            for (UUID uuid : metadata.getTunnelZones()) {
                ops.add(
                        zk.getPersistentCreateOp(
                        paths.getHostTunnelZonePath(hostId, uuid), null
                    ));
            }

            zk.multi(ops);
        } catch (SerializationException e) {
            throw new SerializationException(
                "Could not serialize host metadata for id: " + hostId,
                e, HostDirectory.Metadata.class);
        }
    }

    public void makeAlive(UUID hostId) throws StateAccessException,
            SerializationException {
        makeAlive(hostId, null);
    }

    public void makeAlive(UUID hostId, HostDirectory.Metadata metadata)
            throws StateAccessException, SerializationException {
        zk.addEphemeral(paths.getHostPath(hostId) + "/alive",
                new byte[0]);
        updateMetadata(hostId, metadata);
    }


    /*
     * create the host version path if it does not exist, then update our
     * version in ZK. This value will be read by the upgrade coordinator
     * to see which version everyone is on.
     */
    public void setHostVersion(UUID hostId) throws StateAccessException {
        List<Op> operations = new ArrayList<Op>();
        String versionDirPath = paths.getVersionDirPath();
        if (!zk.exists(versionDirPath)) {
            operations.add(zk.getPersistentCreateOp(versionDirPath, null));
        }
        String versionPath = paths.getVersionPath(DataWriteVersion.CURRENT);
        if (!zk.exists(versionPath)) {
            operations.add(zk.getPersistentCreateOp(versionPath, null));
        }
        zk.multi(operations);
        zk.addEphemeral(paths.getHostVersionPath(hostId, DataWriteVersion.CURRENT),
                     new byte[0]);
    }

    public void updateMetadata(UUID hostId, HostDirectory.Metadata metadata)
            throws StateAccessException, SerializationException {
        if (metadata != null) {
            try {
                zk.update(paths.getHostPath(hostId),
                        serializer.serialize(metadata));
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

    public boolean hostExists(UUID id) throws StateAccessException {
        return zk.exists(paths.getHostPath(id));
    }

    public boolean hasPortBindings(UUID id) throws StateAccessException {
        Set<String> portChildren =
            zk.getChildren(paths.getHostVrnPortMappingsPath(id));

        return !portChildren.isEmpty();
    }

    public void deleteHost(UUID id) throws StateAccessException {
        String hostEntryPath = paths.getHostPath(id);

        List<Op> delMulti = new ArrayList<Op>();

        if (zk.exists(paths.getHostCommandsPath(id))) {
            delMulti.addAll(
                    zk.getRecursiveDeleteOps(paths.getHostCommandsPath(id)));
        }

        if (zk.exists(paths.getHostCommandErrorLogsPath(id))) {
            delMulti.addAll(
                    zk.getRecursiveDeleteOps(
                    paths.getHostCommandErrorLogsPath(id)));
        }

        if (zk.exists(paths.getHostInterfacesPath(id))) {
            delMulti.add(zk.getDeleteOp(paths.getHostInterfacesPath(id)));
        }

        if (zk.exists(paths.getHostVrnPortMappingsPath(id))) {
            delMulti.add(zk.getDeleteOp(paths.getHostVrnPortMappingsPath(id)));
        }

        if (zk.exists(paths.getHostVrnDatapathMappingPath(id))) {
            delMulti.add(zk.getDeleteOp(paths.getHostVrnDatapathMappingPath(id)));
        }

        if (zk.exists(paths.getHostVrnMappingsPath(id))) {
            delMulti.add(zk.getDeleteOp(paths.getHostVrnMappingsPath(id)));
        }

        Set<UUID> tunnelZones = getTunnelZoneIds(id, null);
        for (UUID zoneId : tunnelZones) {
            delMulti.add(
                    zk.getDeleteOp(
                    paths.getHostTunnelZonePath(id, zoneId)));

            delMulti.add(
                    zk.getDeleteOp(
                    paths.getTunnelZoneMembershipPath(zoneId, id)));
        }

        if (zk.exists(paths.getHostTunnelZonesPath(id))) {
            delMulti.add(
                    zk.getDeleteOp(paths.getHostTunnelZonesPath(id)));
        }

        delMulti.add(zk.getDeleteOp(hostEntryPath));

        zk.multi(delMulti);
    }

    public Set<UUID> getHostIds() throws StateAccessException {
        return getHostIds(null);
    }

    public Set<UUID> getHostIds(Directory.TypedWatcher watcher)
        throws StateAccessException {
        String path = paths.getHostsPath();
        Set<String> ids = zk.getChildren(path, watcher);
        Set<UUID> uuids = new HashSet<UUID>();

        for (String id : ids) {
            uuids.add(UUID.fromString(id));
        }

        return uuids;
    }

    public Integer createHostCommandId(UUID hostId, Command command)
            throws StateAccessException, SerializationException {

        try {
            String path = zk.addPersistentSequential(
                paths.getHostCommandsPath(hostId),
                serializer.serialize(command));

            int idx = path.lastIndexOf('/');
            return Integer.parseInt(path.substring(idx + 1));

        } catch (SerializationException e) {
            throw new SerializationException(
                "Could not serialize host command for id: " + hostId, e,
                Command.class);
        }
    }

    public boolean existsInterface(UUID hostId, String interfaceName)
        throws StateAccessException {
        return zk.exists(paths.getHostInterfacePath(hostId,
                                                       interfaceName));
    }

    public void createInterface(UUID hostId,
                                HostDirectory.Interface anInterface)
            throws StateAccessException, IOException, SerializationException {

        List<Op> ops = new ArrayList<Op>();

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

    public List<Integer> listCommandIds(UUID hostId, Runnable watcher)
        throws StateAccessException {
        List<Integer> result = new ArrayList<Integer>();

        String hostCommandsPath = paths.getHostCommandsPath(hostId);
        Set<String> commands = zk.getChildren(hostCommandsPath, watcher);
        for (String commandId : commands) {
            try {
                result.add(Integer.parseInt(commandId));
            } catch (NumberFormatException e) {
                log.warn("HostCommand id is not a number: {} (for host: {}",
                         commandId, hostId);
            }
        }

        return result;
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
    public Set<String> getInterfaces(UUID hostId)
        throws StateAccessException {

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

        List<Op> updateInterfacesOperation = new ArrayList<Op>();

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

        if (updateInterfacesOperation.size() > 0) {
            zk.multi(updateInterfacesOperation);
        }
    }

    public List<Integer> getCommandIds(UUID hostId)
        throws StateAccessException {

        Set<String> commandIdKeys = zk.getChildren(
            paths.getHostCommandsPath(hostId));

        List<Integer> commandIds = new ArrayList<Integer>();
        for (String commandIdKey : commandIdKeys) {
            try {
                commandIds.add(Integer.parseInt(commandIdKey));
            } catch (NumberFormatException e) {
                log.warn("Command key could not be converted to a number: " +
                             "host {}, command {}", hostId, commandIdKey);
            }
        }

        Collections.sort(commandIds);

        return commandIds;
    }

    public Command getCommandData(UUID hostId, Integer commandId)
            throws StateAccessException, SerializationException {

        try {
            byte[] data = zk.get(
                paths.getHostCommandPath(hostId, commandId));

            return serializer.deserialize(data, Command.class);
        } catch (SerializationException e) {
            throw new SerializationException(
                "Could not deserialize host command data id: " +
                    hostId + " / " + commandId, e, Command.class);
        }
    }

    public void deleteHostCommand(UUID hostId, Integer id)
        throws StateAccessException {

        String commandPath = paths.getHostCommandPath(hostId, id);

        List<Op> delete = zk.getRecursiveDeleteOps(commandPath);

        zk.multi(delete);
    }

    public void setCommandErrorLogEntry(UUID hostId,
                                        HostDirectory.ErrorLogItem errorLog)
            throws StateAccessException, SerializationException {
        String path = paths.getHostCommandErrorLogsPath(hostId) + "/"
            + String.format("%010d", errorLog.getCommandId());
        if (!(zk.exists(path))) {
            zk.addPersistent(path, null);
        }
        try {
            // Assign to the error log the same id of the command that generated it
            zk.update(path, serializer.serialize(errorLog));

        } catch (SerializationException e) {
            throw new SerializationException(
                "Could not serialize host metadata for id: " + hostId,
                e, HostDirectory.Metadata.class);
        }
    }

    public HostDirectory.ErrorLogItem getErrorLogData(UUID hostId, Integer logId)
            throws StateAccessException, SerializationException {
        try {
            String errorItemPath =
                paths.getHostCommandErrorLogPath(hostId, logId);

            if (zk.exists(errorItemPath)) {
                byte[] data = zk.get(errorItemPath);
                return serializer.deserialize(data,
                        HostDirectory.ErrorLogItem.class);
            }

            return null;
        } catch (SerializationException e) {
            throw new SerializationException(
                "Could not deserialize host error log data id: " +
                    hostId + " / " + logId, e,
                HostDirectory.ErrorLogItem.class);
        }
    }

    public List<Integer> listErrorLogIds(UUID hostId)
        throws StateAccessException {
        List<Integer> result = new ArrayList<Integer>();
        String hostLogsPath = paths.getHostCommandErrorLogsPath(hostId);
        Set<String> logs = zk.getChildren(hostLogsPath, null);
        for (String logId : logs) {
            // For now, get each one.
            try {
                result.add(Integer.parseInt(logId));
            } catch (NumberFormatException e) {
                log.warn("ErrorLog id is not a number: {} (for host: {}",
                         logId, hostId);
            }
        }

        return result;
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
            UUID hostIdentifier, Directory.TypedWatcher watcher)
            throws StateAccessException, SerializationException {

        String virtualPortMappingPath =
            paths.getHostVrnPortMappingsPath(hostIdentifier);

        Set<HostDirectory.VirtualPortMapping> portMappings =
            new HashSet<HostDirectory.VirtualPortMapping>();

        if (zk.exists(virtualPortMappingPath)) {
            Set<String> children = zk.getChildren(virtualPortMappingPath, watcher);
            for (String child : children) {
                portMappings.add(
                    serializer.deserialize(
                            zk.get(paths.getHostVrnPortMappingPath(
                                   hostIdentifier,
                                   UUID.fromString(child))),
                            HostDirectory.VirtualPortMapping.class
                        )
                );
            }
        }

        return portMappings;
    }

    public void addVirtualDatapathMapping(UUID hostIdentifier,
                                          String datapathMapping)
            throws StateAccessException, SerializationException {

        List<Op> operations = new ArrayList<Op>();

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

    public Port addVirtualPortMapping(
            UUID hostIdentifier, HostDirectory.VirtualPortMapping portMapping)
            throws StateAccessException, SerializationException {

        // Make sure that the portID is valid
        UUID portId = portMapping.getVirtualPortId();
        if (!portZkManager.exists(portId)) {
            throw new IllegalArgumentException(
                "Port with ID " + portId + " does not exist");
        }

        List<Op> operations = new ArrayList<Op>();

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

        Port updatedPort = Converter.fromPortConfig(port);
        updatedPort.setId(portId);
        return updatedPort;
    }

    public void delVirtualPortMapping(UUID hostIdentifier, UUID portId)
            throws StateAccessException, SerializationException {

        String virtualMappingPath =
            paths.getHostVrnPortMappingPath(hostIdentifier, portId);

        List<Op> ops = new ArrayList<Op>();
        if (zk.exists(virtualMappingPath)) {

            ops.add(zk.getDeleteOp(virtualMappingPath));

            PortConfig port = portZkManager.get(portId);

            // Update the port config
            ops.add(getMapUpdatePortOp(port, portId, null, null));

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
        return zk.getSetDataOp(portPath, serializer.serialize(port));
    }

    public Set<UUID> getTunnelZoneIds(UUID hostId,
                                      Directory.TypedWatcher watcher)
        throws StateAccessException {
        String zonesPath = paths.getHostTunnelZonesPath(hostId);

        if (zk.exists(zonesPath)) {
            return CollectionFunctors.map(
                    zk.getChildren(zonesPath, watcher),
                new Functor<String, UUID>() {
                    @Override
                    public UUID apply(String arg0) {
                        return UUID.fromString(arg0);
                    }
                }, new HashSet<UUID>());
        }

        return Collections.emptySet();
    }

}
