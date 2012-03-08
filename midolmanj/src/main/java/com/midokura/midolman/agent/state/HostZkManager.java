/*
 * Copyright 2012 Midokura Europe SARL
 */
package com.midokura.midolman.agent.state;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.apache.zookeeper.Op;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.state.Directory;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midolman.state.ZkManager;
import com.midokura.midolman.state.ZkNodeEntry;
import com.midokura.midolman.state.ZkStateSerializationException;
import static com.midokura.midolman.agent.state.HostDirectory.Command;

/**
 * Wrapper class over a Directory that handled setting and reading data related
 * to hosts and interfaces associated with the hosts.
 *
 * @author Mihai Claudiu Toader <mtoader@midokura.com>
 *         Date: 1/31/12
 */
public class HostZkManager extends ZkManager {

    private final static Logger log =
        LoggerFactory.getLogger(HostZkManager.class);

    public HostZkManager(Directory zk, String basePath) {
        super(zk, basePath);
    }

    public ZkNodeEntry<UUID, HostDirectory.Metadata> getHostMetadata(UUID id)
        throws StateAccessException {
        byte[] data = get(pathManager.getHostPath(id));
        HostDirectory.Metadata metadata;
        try {
            metadata = deserialize(data, HostDirectory.Metadata.class);
        } catch (IOException e) {
            String dataAsString = new String(data);
            throw new ZkStateSerializationException(
                "Could not deserialize host metadata for id: " + id +
                    " [" + dataAsString + "]",
                e,
                HostDirectory.Metadata.class);
        }
        return new ZkNodeEntry<UUID, HostDirectory.Metadata>(id, metadata);
    }

    public void createHost(UUID hostId, HostDirectory.Metadata metadata)
        throws StateAccessException {

        try {
            List<Op> createMulti = new ArrayList<Op>();
            createMulti.add(
                getPersistentCreateOp(pathManager.getHostPath(hostId),
                                      serialize(metadata)));
            createMulti.add(
                getPersistentCreateOp(pathManager.getHostInterfacesPath(hostId),
                                      null));
            createMulti.add(
                getPersistentCreateOp(pathManager.getHostCommandsPath(hostId),
                                      null));

            multi(createMulti);
        } catch (IOException e) {
            throw new ZkStateSerializationException(
                "Could not serialize host metadata for id: " + hostId,
                e, HostDirectory.Metadata.class);
        }
    }

    public void makeAlive(UUID hostId) throws StateAccessException {
        makeAlive(hostId, null);
    }

    public void makeAlive(UUID hostId, HostDirectory.Metadata metadata)
        throws StateAccessException {
        addEphemeral(pathManager.getHostPath(hostId) + "/alive",
                     new byte[0]);
        updateMetadata(hostId, metadata);
    }

    public void updateMetadata(UUID hostId, HostDirectory.Metadata metadata)
        throws StateAccessException {
        if (metadata != null) {
            try {
                update(pathManager.getHostPath(hostId), serialize(metadata));
            } catch (IOException e) {
                throw new ZkStateSerializationException(
                    "Could not serialize host metadata for id: " + hostId,
                    e, HostDirectory.Metadata.class);
            }
        }
    }

    public boolean isAlive(UUID id) throws StateAccessException {
        return exists(pathManager.getHostPath(id) + "/alive");
    }

    public boolean hostExists(UUID id) throws StateAccessException {
        return exists(pathManager.getHostPath(id));
    }

    public void deleteHost(UUID id) throws StateAccessException {
        String hostEntryPath = pathManager.getHostPath(id);

        List<Op> delMulti = new ArrayList<Op>();

        if (exists(pathManager.getHostCommandsPath(id))) {
            delMulti.addAll(
                getRecursiveDeleteOps(pathManager.getHostCommandsPath(id)));
        }
        if (exists(pathManager.getHostInterfacesPath(id))) {
            delMulti.add(getDeleteOp(pathManager.getHostInterfacesPath(id)));
        }
        delMulti.add(getDeleteOp(hostEntryPath));

        multi(delMulti);
    }

    public Integer createHostCommand(UUID hostId, Command command)
        throws StateAccessException {

        try {
            String path = addPersistentSequential(
                pathManager.getHostCommandsPath(hostId), serialize(command));

            int idx = path.lastIndexOf('/');
            return Integer.parseInt(path.substring(idx + 1));

        } catch (IOException e) {
            throw new ZkStateSerializationException(
                "Could not serialize host command for id: " + hostId, e,
                Command.class);
        }
    }

    public UUID createInterface(UUID hostId,
                                HostDirectory.Interface anInterface)
        throws StateAccessException, IOException {

        List<Op> ops = new ArrayList<Op>();

        UUID uuid = UUID.randomUUID();
        if (!exists(pathManager.getHostInterfacesPath(hostId))) {
            ops.add(getPersistentCreateOp(
                pathManager.getHostInterfacesPath(hostId), null));
        }
        ops.add(getEphemeralCreateOp(
            pathManager.getHostInterfacePath(hostId, uuid),
            serialize(anInterface)));

        multi(ops);

        return uuid;
    }

    public ZkNodeEntry<UUID, HostDirectory.Interface> getInterfaceData(
        UUID hostId, UUID interfaceId) throws StateAccessException {

        try {
            byte[] data = get(
                pathManager.getHostInterfacePath(hostId, interfaceId));

            return new ZkNodeEntry<UUID, HostDirectory.Interface>(
                interfaceId,
                deserialize(data, HostDirectory.Interface.class)
            );
        } catch (IOException e) {
            throw new ZkStateSerializationException(
                "Could not deserialize host interface metadata for id: " +
                    hostId + " / " + interfaceId,
                e, HostDirectory.Metadata.class);
        }
    }

    public List<ZkNodeEntry<Integer, HostDirectory.Command>> list(UUID hostId,
                                                                  Runnable watcher)
        throws StateAccessException {
        List<ZkNodeEntry<Integer, HostDirectory.Command>> result =
            new ArrayList<ZkNodeEntry<Integer, HostDirectory.Command>>();

        // TODO is the watcher on the children or on the folder?
        String hostCommandsPath = pathManager.getHostCommandsPath(hostId);
        Set<String> commands = getChildren(hostCommandsPath, watcher);
        for (String commandId : commands) {
            // For now, get each one.
            try {
                result.add(getCommandData(hostId, Integer.parseInt(commandId)));
            } catch (NumberFormatException e) {
                log.warn("HostCommand id is not a number: {} (for host: {}",
                         commandId, hostId);
            }
        }

        return result;
    }
    
    private List<Op> getRecursiveDeleteOps(String root)
        throws StateAccessException {
        return recursiveBottomUpFold(root, new Functor<String, Op>() {
            @Override
            public Op process(String node) {
                return Op.delete(node, -1);
            }
        }, new ArrayList<Op>());
    }

    /**
     * Will return the collection of UUID for all the interfaces presently
     * described under the provided host entry.
     *
     * @param hostId the host id for which we want the interfaces
     * @return the collection of interface keys
     * @throws StateAccessException if we weren't able to properly communicate
     *                              with the datastore.
     */
    public Set<UUID> getInterfaceIds(UUID hostId)
        throws StateAccessException {

        Set<String> interfaceKeys = getChildren(
            pathManager.getHostInterfacesPath(hostId));

        Set<UUID> uuids = new HashSet<UUID>();
        for (String key : interfaceKeys) {
            try {
                uuids.add(UUID.fromString(key));
            } catch (Exception e) {
                log.warn("Interface identifier couldn't be converted into a " +
                             "UUID: {} (for host {})", key, hostId.toString());
            }
        }

        return uuids;
    }

    public void updateHostInterfaces(UUID hostId,
                                     List<HostDirectory.Interface> currentInterfaces,
                                     Set<UUID> obsoleteInterfaces)
        throws StateAccessException {

        List<Op> updateInterfacesOperation = new ArrayList<Op>();

        for (UUID obsoleteInterface : obsoleteInterfaces) {
            updateInterfacesOperation.add(
                getDeleteOp(
                    pathManager.getHostInterfacePath(hostId,
                                                     obsoleteInterface)));
        }

        for (HostDirectory.Interface hostInterface : currentInterfaces) {
            try {

                String hostInterfacePath =
                    pathManager.getHostInterfacePath(hostId,
                                                     hostInterface.getId());
                byte[] serializedData = serialize(hostInterface);

                Op hostInterfaceOp;
                if (exists(hostInterfacePath)) {
                    hostInterfaceOp =
                        getSetDataOp(hostInterfacePath, serializedData);
                } else {
                    hostInterfaceOp =
                        getEphemeralCreateOp(hostInterfacePath, serializedData);
                }

                updateInterfacesOperation.add(hostInterfaceOp);

            } catch (IOException ex) {
                log.warn("Could not serialize interface data {}.",
                         hostInterface, ex);
            }
        }

        if (updateInterfacesOperation.size() > 0) {
            multi(updateInterfacesOperation);
        }
    }

    public List<Integer> getCommandIds(UUID hostId)
        throws StateAccessException {

        Set<String> commandIdKeys = getChildren(
            pathManager.getHostCommandsPath(hostId));

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

    public ZkNodeEntry<Integer, Command> getCommandData(UUID hostId,
                                                        Integer commandId)
        throws StateAccessException {

        try {
            byte[] data = get(
                pathManager.getHostCommandPath(hostId, commandId));

            return new ZkNodeEntry<Integer, Command>(
                commandId,
                deserialize(data, Command.class)
            );
        } catch (IOException e) {
            throw new ZkStateSerializationException(
                "Could not deserialize host command data id: " +
                    hostId + " / " + commandId, e, Command.class);
        }
    }

    public void deleteHostCommand(UUID hostId, Integer id)
        throws StateAccessException {

        String commandPath = pathManager.getHostCommandPath(hostId, id);

        List<Op> delete = getRecursiveDeleteOps(commandPath);

        multi(delete);
    }

    interface Functor<S, T> {
        public T process(S node);
    }

    private <T> List<T> recursiveBottomUpFold(String root,
                                              Functor<String, T> func,
                                              List<T> acc)
        throws StateAccessException {

        Set<String> children = getChildren(root);

        for (String child : children) {
            recursiveBottomUpFold(root + "/" + child, func, acc);
        }

        T processedRoot = func.process(root);
        if (processedRoot != null) {
            acc.add(processedRoot);
        }

        return acc;
    }
}
