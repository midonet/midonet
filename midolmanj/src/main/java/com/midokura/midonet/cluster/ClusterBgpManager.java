/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package com.midokura.midonet.cluster;

import com.midokura.midolman.state.BgpConfigCache;
import com.midokura.midonet.cluster.client.*;
import com.midokura.midonet.cluster.data.BGP;
import com.midokura.util.functors.Callback1;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.util.UUID;

public class ClusterBgpManager extends ClusterManager<BGPListBuilder> {
    BgpConfigCache bgpConfigCache;

    private static final Logger log = LoggerFactory
            .getLogger(ClusterBgpManager.class);

    @Inject
    public ClusterBgpManager(BgpConfigCache bgpConfigCache) {
        this.bgpConfigCache = bgpConfigCache;
        bgpConfigCache.addWatcher(getBgpsWatcher());
    }

    @Override
    public Runnable getConfig(final UUID bgpPortID) {
        return new Runnable() {

            @Override
            public void run() {
                BGP config = bgpConfigCache.get(bgpPortID);
                BGPListBuilder builder = getBuilder(bgpPortID);
                builder.addBGP(config);
                // no .build() method in this interface
                //TODO(abel) consider also updates/deletes
            }
        };
    }

    public Callback1<UUID> getBgpsWatcher(){
        return new Callback1<UUID>() {
            @Override
            public void call(UUID bgpId) {
                // this will be executed by the watcher in BgpConfigCache
                // that is triggered by ZkDirectory, that has the same reactor as
                // the cluster client.
                getConfig(bgpId).run();
            }
        };
    }
}
