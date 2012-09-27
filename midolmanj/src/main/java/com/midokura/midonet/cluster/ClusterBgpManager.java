/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package com.midokura.midonet.cluster;

import com.midokura.midolman.state.Directory;
import com.midokura.midolman.state.DirectoryCallback;
import com.midokura.midolman.state.zkManagers.BgpZkManager;
import com.midokura.midonet.cluster.client.*;
import com.midokura.midonet.cluster.data.BGP;
import org.apache.zookeeper.KeeperException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class ClusterBgpManager extends ClusterManager<BGPListBuilder> {
    BgpConfigCache bgpConfigCache = null;

    private static final Logger log = LoggerFactory
            .getLogger(ClusterBgpManager.class);

    //@Inject
    //public ClusterBgpManager(BgpConfigCache bgpConfigCache) {
    //    this.bgpConfigCache = bgpConfigCache;
    //}

    @Override
    protected void getConfig(final UUID bgpPortID) {
            bgpConfigCache.requestBgps(bgpPortID);
    }

    public class BgpConfigCache {

        BgpZkManager bgpMgr = null;

        private Map<UUID, UUID> mapPortIdtoBgpId = new HashMap<UUID, UUID>();
        private Map<UUID, UUID> mapBgpIdtoPortId = new HashMap<UUID, UUID>();
        private Map<UUID, BGP> mapBgpIdtoBgp = new HashMap<UUID, BGP>();

        public BgpConfigCache(Directory zkDir, String zkBasePath) {
            //bgpMgr = new BgpZkManager(zkDir, zkBasePath);
        }

        private void requestBgp(UUID bgpID) {
            if (mapBgpIdtoBgp.containsKey(bgpID)) {
                log.error("requestBgp it's only for creations not for updates.");
                return;
            }

            BgpCallback bgpCallback = new BgpCallback(bgpID);
            bgpMgr.getBGPAsync(bgpID, bgpCallback, bgpCallback);
            // TODO(abel) go ahead and fetch the ad routes.
            //adRoutesMgr.getRoutesAsync...
        }

        public void requestBgps(UUID bgpPortID) {
            // this method should be called only once per bgp port ID
            if (mapPortIdtoBgpId.containsKey(bgpPortID)) {
                log.error("trying to request BGPs more than once for this port ID: " + bgpPortID);
                return;
            }

            // One callback to rule them all...
            BgpsCallback bgpsCallback = new BgpsCallback(bgpPortID);
            bgpMgr.getBgpListAsync(bgpPortID, bgpsCallback, bgpsCallback);
        }


        private class BgpCallback implements DirectoryCallback<BGP>,
                    Directory.TypedWatcher {

            UUID bgpID;

            public BgpCallback(UUID bgpID) {
                this.bgpID = bgpID;
            }

            /*
            * DirectoryCallback overrides
            */
            @Override
            public void onSuccess(Result<BGP> data) {
                // We shall receive only updates for this BGP object
                assert (data.getData().getId() == bgpID);

                BGP bgp = data.getData();
                BGPListBuilder bgpListBuilder = getBuilder(bgp.getPortId());
                if (mapBgpIdtoBgp.containsKey(bgp.getId())) {
                    bgpListBuilder.updateBGP(bgp);
                } else {
                    bgpListBuilder.addBGP(bgp);
                }

                mapBgpIdtoBgp.put(bgp.getId(), bgp);
            }

            @Override
            public void onTimeout() {
                log.warn("timeout getting BGPs from cluster");
            }

            @Override
            public void onError(KeeperException e) {
                log.error("Error getting BGPs from cluster: " + e);
            }

            /*
            * TypedWatcher overrides
            */
            @Override
            public void pathDeleted(String path) {
                // The BGP has been deleted. If it has been done correctly,
                // its ID will also be removed from the port's list of BGPs
                // So the callback for the BGP list will take care of
                // notifying the builder.
            }

            @Override
            public void pathCreated(String path) {
                // Should never happen.
                log.error("This shouldn't have been triggered");
            }

            @Override
            public void pathChildrenUpdated(String path) {
                // Should never happen. We didn't subscribe to the children.
                log.error("This shouldn't have been triggered");
            }

            @Override
            public void pathDataChanged(String path) {
                run();
            }

            @Override
            public void pathNoChange(String path) {
                // do nothing
            }

            @Override
            public void run() {
                // The BGP node has changed, fetch it again asynchronously.
                bgpMgr.getBGPAsync(bgpID, this, this);
            }

            /*
             * other methods
             */

        }

        private class BgpsCallback implements DirectoryCallback<Set<UUID>>,
                                                       Directory.TypedWatcher {

            UUID bgpPortID;

            public BgpsCallback(UUID bgpPortID) {
                this.bgpPortID = bgpPortID;
            }

            /*
             * DirectoryCallback overrides
             */
            @Override
            public void onSuccess(Result<Set<UUID>> data) {
                update(data.getData());
            }

            @Override
            public void onTimeout() {
                //TODO(abel) how to deal with this? Schedule a retry later?
                log.error("timeout getting BGPs from cluster");
            }

            @Override
            public void onError(KeeperException e) {
                //TODO(abel) how to deal with this? Schedule a retry later?
                log.error("Error getting BGPs from cluster: " + e);
            }

            /*
             * TypedWatcher overrides
             */
            @Override
            public void pathDeleted(String path) {
                // The port-id/bgps path has been deleted
                log.error("BGP list was deleted at {}", path);
                // TODO(pino): Should probably call Builder.delete()
                //getBuilder(bgpPortID).delete();
            }

            @Override
            public void pathCreated(String path) {
                // Should never happen.
                log.error("This shouldn't have been triggered");
            }

            @Override
            public void pathChildrenUpdated(String path) {
                run();
            }

            @Override
            public void pathDataChanged(String path) {
                // Our watcher is on the children, so this shouldn't be called.
                // Also, we don't use the data part of this path so it should
                // never change.
                log.error("This shouldn't have been triggered.");
            }

            @Override
            public void pathNoChange(String path) {
                // do nothing
                // TODO(pino): when is this triggered?
            }

            @Override
            public void run() {
                // The list of bgp's for this port has changed. Fetch it again
                // asynchronously.
                bgpMgr.getBgpListAsync(bgpPortID, this, this);
            }

            /*
             * other methods
             */
            private void update(Set<UUID> bgpIds) {
                for (UUID bgpId : mapBgpIdtoPortId.keySet()) {
                    if (!bgpIds.contains(bgpId)) {
                        UUID portId = mapBgpIdtoPortId.remove(bgpId);
                        mapPortIdtoBgpId.remove(portId);
                        getBuilder(bgpPortID).removeBGP(bgpId);
                    }
                }

                for (UUID bgpId : bgpIds) {
                    if (!mapBgpIdtoPortId.containsKey(bgpId)) {
                        mapBgpIdtoPortId.put(bgpId, bgpPortID);
                        mapPortIdtoBgpId.put(bgpPortID, bgpId);
                        requestBgp(bgpId);
                    }
                }
            }
        }

    }

}
