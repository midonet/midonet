/*
 * Copyright 2014 Midokura Europe SARL
 */

package org.midonet.cluster;

import java.util.Set;
import java.util.UUID;

import com.google.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.cluster.client.PortGroupBuilder;
import org.midonet.cluster.data.PortGroup;
import org.midonet.midolman.state.PortGroupCache;
import org.midonet.midolman.state.zkManagers.PortGroupZkManager;
import org.midonet.util.functors.Callback1;

public class ClusterPortGroupManager extends ClusterManager<PortGroupBuilder> {
    private static final Logger log =
        LoggerFactory.getLogger(ClusterPortGroupManager.class);

    @Inject
    PortGroupZkManager portGroupMgr;

    @Inject
    PortGroupCache cache;

    @Inject
    public ClusterPortGroupManager(PortGroupCache configCache) {
        cache = configCache;
        configCache.addWatcher(getPortGroupWatcher());
    }

    @Override
    protected void getConfig(final UUID id) {
        PortGroupZkManager.PortGroupConfig config = cache.get(id);
        if (config == null)
            return;

        PortGroupBuilder builder = getBuilder(id);
        PortGroup group = new PortGroup();

        group.setName(config.name);
        group.setStateful(config.stateful);
        group.setId(config.id);
        builder.setConfig(group);

        MembersCallback cb = new MembersCallback(builder, id);
        portGroupMgr.getMembersAsync(id, cb, cb);
    }


    public Callback1<UUID> getPortGroupWatcher(){
        return new Callback1<UUID>() {
            @Override
            public void call(UUID id) {
                // this will be executed by the watcher in PortGroupCache
                // that is triggered by ZkDirectory, that has the same reactor as
                // the cluster client.
                getConfig(id);
            }
        };
    }

    private class MembersCallback extends CallbackWithWatcher<Set<UUID>> {

        PortGroupBuilder builder;
        UUID id;

        public MembersCallback(PortGroupBuilder builder, UUID id) {
            this.builder = builder;
            this.id = id;
        }

        @Override
        protected String describe() {
            return "MembersCallback: " + id;
        }

        /*
         * DirectoryCallback overrides
         */
        @Override
        public void onSuccess(Set<UUID> uuids) {
            log.debug("MembersCallback - begin");
            builder.setMembers(uuids);
        }

        /*
         * TypedWatcher overrides
         */
        @Override
        public void pathChildrenUpdated(String path) {
            log.debug("MembersCallback - begin");
            portGroupMgr.getMembersAsync(id, this, this);
        }

        @Override
        protected Runnable makeRetry() {
            return new Runnable() {
                @Override
                public void run() {
                    portGroupMgr.getMembersAsync(id,
                            MembersCallback.this, MembersCallback.this);
                }
            };
        }
    }
}
