/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.cluster.data.neutron;

import com.google.inject.Inject;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.ZooDefs;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.serialization.Serializer;
import org.midonet.midolman.state.*;
import org.midonet.midolman.state.zkManagers.RouterZkManager;
import org.midonet.midolman.state.zkManagers.RouterZkManager.RouterConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class ProviderRouterZkManager extends BaseZkManager {

    static final Logger log = LoggerFactory.getLogger(
            ProviderRouterZkManager.class);

    private final RouterZkManager routerZkManager;

    @Inject
    public ProviderRouterZkManager(ZkManager zk, PathBuilder paths,
                                   Serializer serializer,
                                   RouterZkManager routerZkManager) {
        super(zk, paths, serializer);
        this.routerZkManager = routerZkManager;
    }

    private ProviderRouter get()
            throws StateAccessException, SerializationException {
        String path = paths.getNeutronProviderRouterPath();
        return serializer.deserialize(zk.get(path), ProviderRouter.class);
    }

    public ProviderRouter getSafe()
            throws StateAccessException, SerializationException {

        try {
            return get();
        } catch (NoStatePathException ex) {
            return null;
        }
   }

    private UUID prepareCreate(List<Op> ops)
            throws SerializationException, StateAccessException {

        String path = paths.getNeutronProviderRouterPath();
        UUID id = UUID.randomUUID();

        RouterConfig cfg = new RouterConfig();
        cfg.adminStateUp = true;
        cfg.name = ProviderRouter.NAME;

        ops.addAll(routerZkManager.prepareRouterCreate(id, cfg));

        // Add the provider router path
        ProviderRouter r = new ProviderRouter(id);
        ops.add(Op.create(path, serializer.serialize(r),
                ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));

        return id;
    }

    /**
     * Get the Provider router ID.  Returns null if it does not exist
     *
     * @return ID of the provider router
     */
    public UUID getId() throws StateAccessException, SerializationException {

        try {
            ProviderRouter r = get();
            log.debug("ProviderRouterZkManager.getId: provider router={}", r);
            return r.id;
        } catch (NoStatePathException ex) {
            throw new IllegalStateException("Provider router not found");
        }
    }

    public UUID getIdSafe()
            throws StateAccessException, SerializationException {

        ProviderRouter r = getSafe();
        return r == null ? null : r.id;
    }

    /**
     * Ensures that there is a provider router.
     *
     * @return Provider router ID
     */
    public UUID ensureExists()
            throws StateAccessException, SerializationException {
        log.debug("ProviderRouterZkManager.ensureExists entered");
        UUID id = getIdSafe();
        if (id != null) {
            return id;
        }

        List<Op> ops = new ArrayList<>();
        prepareCreate(ops);

        try {
            zk.multi(ops);
        } catch (StatePathExistsException ex) {
            // Ignore because it's possible that we get a race here with
            // multiple calls at once to ZK.  As long as one exists, it's ok
            log.warn("Provider already exists");

        }
        return getId();
    }
}
