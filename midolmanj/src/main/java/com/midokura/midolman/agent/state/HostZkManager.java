/*
 * Copyright 2012 Midokura Europe SARL
 */
package com.midokura.midolman.agent.state;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
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

/**
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
            throw new ZkStateSerializationException(
                "Could not deserialize host metadata for id: " + id,
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
        try {
            addEphemeral(pathManager.getHostPath(hostId) + "/alive",
                         new byte[0]);
            if (metadata != null) {
                update(pathManager.getHostPath(hostId), serialize(metadata));
            }
        } catch (IOException e) {
            throw new ZkStateSerializationException(
                "Could not serialize host metadata for id: " + hostId,
                e, HostDirectory.Metadata.class);
        }
    }

    public boolean isAlive(UUID id) throws StateAccessException {
        return exists(pathManager.getHostPath(id) + "/alive");
    }

    public void deleteHost(UUID id) throws StateAccessException {
        String hostEntryPath = pathManager.getHostPath(id);

        List<Op> delMulti = new ArrayList<Op>();

        if (exists(hostEntryPath + "/commands")) {
            delMulti.addAll(getRecursiveDeleteOps(hostEntryPath + "/commands"));
        }
        if (exists(hostEntryPath + "/interfaces")) {
            delMulti.add(getDeleteOp(hostEntryPath + "/interfaces"));
        }
        delMulti.add(getDeleteOp(hostEntryPath));

        multi(delMulti);
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

    public Collection<UUID> getInterfaceIds(UUID hostId)
        throws StateAccessException {
        Set<String> interfaceIdStrings =
            getChildren(pathManager.getHostInterfacesPath(hostId));

        Set<UUID> interfaceIds = new HashSet<UUID>();

        for (String interfaceIdString : interfaceIdStrings) {
            try {
                interfaceIds.add(UUID.fromString(interfaceIdString));
            } catch (Exception e) {
                log.warn(
                    "Exception while converting an interface child entry to an uuid",
                    e);
            }
        }

        return interfaceIds;
    }

    interface Functor<S, T> {
        public T process(S node);
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
