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

package org.midonet.cluster;

import java.util.Set;
import java.util.UUID;
import javax.inject.Named;

import com.google.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rx.functions.Action1;
import rx.functions.Func1;

import org.midonet.cluster.client.PortGroupBuilder;
import org.midonet.cluster.data.PortGroup;
import org.midonet.midolman.cluster.zookeeper.ZkConnectionProvider;
import org.midonet.midolman.state.NoStatePathException;
import org.midonet.midolman.state.PortGroupCache;
import org.midonet.midolman.state.zkManagers.PortGroupZkManager;
import org.midonet.util.eventloop.Reactor;
import org.midonet.util.functors.Callback1;

public class ClusterPortGroupManager extends ClusterManager<PortGroupBuilder> {
    private static final Logger log =
        LoggerFactory.getLogger(ClusterPortGroupManager.class);

    @Inject
    PortGroupZkManager portGroupMgr;

    @Inject
    PortGroupCache cache;

    @Inject
    @Named(ZkConnectionProvider.DIRECTORY_REACTOR_TAG)
    Reactor reactorLoop;

    @Inject
    public ClusterPortGroupManager(PortGroupCache configCache) {
        cache = configCache;
        configCache.addWatcher(getPortGroupWatcher());
    }

    @Override
    protected void getConfig(final UUID id) {
        cache.get(id).observeOn(reactorLoop.rxScheduler())
            .filter(
                new Func1<PortGroupZkManager.PortGroupConfig, Boolean>() {
                    @Override
                    public Boolean call(PortGroupZkManager.PortGroupConfig config) {
                        return config != null;
                    }
                })
            .subscribe(
                new Action1<PortGroupZkManager.PortGroupConfig>() {
                    @Override
                    public void call(PortGroupZkManager.PortGroupConfig config) {
                        PortGroupBuilder builder = getBuilder(id);
                        if (builder == null) return;

                        PortGroup group = new PortGroup();

                        group.setName(config.name);
                        group.setStateful(config.stateful);
                        group.setId(config.id);
                        builder.setConfig(group);

                        MembersCallback cb = new MembersCallback(builder, id);
                        portGroupMgr.getMembersAsync(id, cb, cb);
                    }
                },
                new Action1<Throwable>() {
                    @Override
                    public void call(Throwable t) {
                        if (t instanceof NoStatePathException) {
                            PortGroupBuilder builder = unregisterBuilder(id);
                            if (builder != null) {
                                builder.deleted();
                            }
                        } else {
                            log.info("Exception thrown getting config for {}",
                                     id, t);
                        }
                    }
                });
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
