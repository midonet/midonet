/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package com.midokura.midonet.cluster;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.google.inject.Inject;
import com.midokura.midolman.state.Directory;
import com.midokura.midolman.state.DirectoryCallback;
import com.midokura.midolman.state.zkManagers.AdRouteZkManager;
import com.midokura.midolman.state.zkManagers.BgpZkManager;
import com.midokura.midonet.cluster.client.*;
import com.midokura.midonet.cluster.data.AdRoute;
import com.midokura.midonet.cluster.data.BGP;
import org.apache.zookeeper.KeeperException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class ClusterBgpManager extends ClusterManager<BGPListBuilder> {

    private static final Logger log = LoggerFactory
            .getLogger(ClusterBgpManager.class);

    @Inject
    BgpZkManager bgpMgr;

    @Inject
    AdRouteZkManager adRouteMgr;

    private Set<UUID> portIds = new HashSet<UUID>();
    private Map<UUID, UUID> mapBgpIdtoPortId = new HashMap<UUID, UUID>();
    private Map<UUID, BGP> mapBgpIdtoBgp = new HashMap<UUID, BGP>();
    private Multimap<UUID, UUID> mmapBgpIdtoAdRouteId = HashMultimap.create();
    private Map<UUID, AdRouteZkManager.AdRouteConfig> mapAdRouteIdtoAdRouteConfig =
            new HashMap<UUID, AdRouteZkManager.AdRouteConfig>();

    @Override
    protected void getConfig(final UUID bgpPortID) {
            requestBgps(bgpPortID);
    }

    /*
     * In order to decouple Zk from the Builder, we need to convert
     * AdRouteConfig to AdRoute.
     */
    private static AdRoute getAdRoute(AdRouteZkManager.AdRouteConfig adRouteConfig) {
        return new AdRoute()
                .setBgpId(adRouteConfig.bgpId)
                .setNwPrefix(adRouteConfig.nwPrefix)
                .setPrefixLength(adRouteConfig.prefixLength);
    }

    private void requestBgp(UUID bgpID) {
        if (mapBgpIdtoBgp.containsKey(bgpID)) {
            log.error("requestBgp it's only for creations not for updates.");
            return;
        }

        BgpCallback bgpCallback = new BgpCallback(bgpID);
        bgpMgr.getBGPAsync(bgpID, bgpCallback, bgpCallback);

        AdRoutesCallback adRoutesCallback = new AdRoutesCallback(bgpID);
        adRouteMgr.getAdRouteListAsync(bgpID, adRoutesCallback, adRoutesCallback);
    }

    public void requestBgps(UUID bgpPortID) {
        // this method should be called only once per bgp port ID
        if (portIds.contains(bgpPortID)) {
            log.error("trying to request BGPs more than once for this port ID: " + bgpPortID);
            return;
        }

        // One callback to rule them all...
        BgpsCallback bgpsCallback = new BgpsCallback(bgpPortID);
        bgpMgr.getBgpListAsync(bgpPortID, bgpsCallback, bgpsCallback);
    }

    private void requestAdRoute(UUID adRouteId) {
        if (mmapBgpIdtoAdRouteId.containsValue(adRouteId)) {
            log.error("requestAdRoute it's only for creations not for updates.");
            return;
        }

        AdRouteCallback adRouteCallback = new AdRouteCallback(adRouteId);
        adRouteMgr.getAdRouteAsync(adRouteId, adRouteCallback, adRouteCallback);
    }

    private class BgpCallback implements DirectoryCallback<BGP>,
                Directory.TypedWatcher {

        //TODO(abel) consider having one callback for all BGPS instead
        // of one callback per BGP.
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
            // The BGP node has changed, fetch it again asynchronously.
            bgpMgr.getBGPAsync(bgpID, this, this);
        }

        @Override
        public void pathNoChange(String path) {
            // do nothing
        }

        @Override
        public void run() {
            log.error("Should NEVER be called.");
        }
    }

    private class BgpsCallback implements DirectoryCallback<Set<UUID>>,
                                                   Directory.TypedWatcher {

        //TODO(abel) consider having one callback for all Port IDs instead
        //of one callback per each port ID.
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
            // The list of bgp's for this port has changed. Fetch it again
            // asynchronously.
            bgpMgr.getBgpListAsync(bgpPortID, this, this);
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
            log.error("Should NEVER be called.");
        }

        /*
         * other methods
         */
        private void update(Set<UUID> bgpIds) {
            for (UUID bgpId : mapBgpIdtoPortId.keySet()) {
                if (!bgpIds.contains(bgpId)) {
                    UUID portId = mapBgpIdtoPortId.remove(bgpId);
                    portIds.remove(portId);
                    getBuilder(bgpPortID).removeBGP(bgpId);
                }
            }

            for (UUID bgpId : bgpIds) {
                if (!mapBgpIdtoPortId.containsKey(bgpId)) {
                    mapBgpIdtoPortId.put(bgpId, bgpPortID);
                    portIds.add(bgpPortID);
                    requestBgp(bgpId);
                }
            }
        }
    }

    private class AdRouteCallback implements DirectoryCallback<AdRouteZkManager.AdRouteConfig>,
            Directory.TypedWatcher {

        //TODO(abel) consider having one callback for all AdRoutes instead
        // of one callback per AdRoute.
        UUID adRouteId;

        public AdRouteCallback(UUID adRouteId) {
            this.adRouteId = adRouteId;
        }

        /*
        * DirectoryCallback overrides
        */
        @Override
        public void onSuccess(Result<AdRouteZkManager.AdRouteConfig> data) {
            AdRouteZkManager.AdRouteConfig adRouteConfig = data.getData();
            BGPListBuilder bgpListBuilder = getBuilder(mapBgpIdtoPortId.get(adRouteConfig.bgpId));

            if (mmapBgpIdtoAdRouteId.containsValue(adRouteId)) {
                //update not supported for AdRoutes at this moment
            } else {
                bgpListBuilder.addAdvertisedRoute(getAdRoute(adRouteConfig));
                mmapBgpIdtoAdRouteId.put(adRouteConfig.bgpId, adRouteId);
                mapAdRouteIdtoAdRouteConfig.put(adRouteId, adRouteConfig);
            }
        }

        @Override
        public void onTimeout() {
            log.warn("timeout getting AdRoute from cluster");
        }

        @Override
        public void onError(KeeperException e) {
            log.error("Error getting AdRoute from cluster: " + e);
        }

        /*
        * TypedWatcher overrides
        */
        @Override
        public void pathDeleted(String path) {
            // taken care by AdRoutesCallback
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
            // The AdRoute node has changed, fetch it again asynchronously.
            adRouteMgr.getAdRouteAsync(adRouteId, this, this);
        }

        @Override
        public void pathNoChange(String path) {
            // do nothing
        }

        @Override
        public void run() {
            log.error("Should NEVER be called.");
        }

    }

    private class AdRoutesCallback implements DirectoryCallback<Set<UUID>>,
            Directory.TypedWatcher {

        //TODO(abel) consider having one callback for all BGP IDs, instead
        // of one callback per BGP.
        UUID bgpID;

        public AdRoutesCallback(UUID bgpID) {
            this.bgpID = bgpID;
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
            log.error("timeout getting AdRoutes from cluster");
        }

        @Override
        public void onError(KeeperException e) {
            //TODO(abel) how to deal with this? Schedule a retry later?
            log.error("Error getting AdRoutes from cluster: " + e);
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
            // The list of AdRoutes for this BGP has changed. Fetch it again
            // asynchronously.
            adRouteMgr.getAdRouteListAsync(bgpID, this, this);
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
            log.error("Should NEVER be called.");
        }

        /*
         * other methods
         */
        private void update(Set<UUID> adRouteIds) {
            for (UUID adRouteId : mmapBgpIdtoAdRouteId.get(bgpID)) {
                if (!adRouteIds.contains(adRouteId)) {
                    mmapBgpIdtoAdRouteId.remove(bgpID, adRouteId);

                    UUID portId = mapBgpIdtoPortId.get(bgpID);

                    AdRouteZkManager.AdRouteConfig adRouteConfig =
                            mapAdRouteIdtoAdRouteConfig.remove(adRouteId);
                    getBuilder(portId).removeAdvertisedRoute(getAdRoute(adRouteConfig));
                }
            }

            for (UUID adRouteId : adRouteIds) {
                if (!mmapBgpIdtoAdRouteId.containsValue(adRouteId)) {
                    mmapBgpIdtoAdRouteId.put(bgpID, adRouteId);
                    requestAdRoute(adRouteId);
               }
            }
        }
    }

}
