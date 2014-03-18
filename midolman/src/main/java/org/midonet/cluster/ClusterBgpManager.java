/*
 * Copyright (c) 2012-2014 Midokura Europe SARL, All Rights Reserved.
 */

package org.midonet.cluster;

import com.google.common.base.Objects;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.google.inject.Inject;
import org.midonet.midolman.state.zkManagers.AdRouteZkManager;
import org.midonet.midolman.state.zkManagers.BgpZkManager;
import org.midonet.cluster.client.*;
import org.midonet.cluster.data.AdRoute;
import org.midonet.cluster.data.BGP;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
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

    //private Set<UUID> portIds = new HashSet<UUID>();
    //TODO(abel) use a more efficient data structure if we use lots of bgps per host
    private Multimap<UUID, UUID> mmapPortIdtoBgpIds = HashMultimap.create();
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
        log.debug("requesting bgp with id {}", bgpID);
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
        log.debug("requesting list of bgp's for port {}", bgpPortID);

        //TODO(abel) add proper synchronization method
        // this method should be called only once per bgp port ID
        if (mmapPortIdtoBgpIds.containsKey(bgpPortID)) {
            log.error("trying to request BGPs more than once for this port ID: " + bgpPortID);
            return;
        }

        // One callback to rule them all...
        BgpsCallback bgpsCallback = new BgpsCallback(bgpPortID);
        bgpMgr.getBgpListAsync(bgpPortID, bgpsCallback, bgpsCallback);
    }

    private void requestAdRoute(UUID adRouteId) {
        log.debug("requesting adRoute");
        if (mmapBgpIdtoAdRouteId.containsValue(adRouteId)) {
            log.error("requestAdRoute it's only for creations not for updates.");
            return;
        }

        AdRouteCallback adRouteCallback = new AdRouteCallback(adRouteId);
        adRouteMgr.getAsync(adRouteId, adRouteCallback, adRouteCallback);
    }

    private class BgpCallback extends CallbackWithWatcher<BGP> {

        //TODO(abel) consider having one callback for all BGPS instead
        // of one callback per BGP.
        UUID bgpID;

        public BgpCallback(UUID bgpID) {
            this.bgpID = bgpID;
        }

        @Override
        protected String describe() {
            return "BGP:" + bgpID;
        }

        /*
        * DirectoryCallback overrides
        */
        @Override
        public void onSuccess(Result<BGP> data) {
            log.debug("begin");
            // We shall receive only updates for this BGP object
            if (!Objects.equal(data.getData().getId(), bgpID)) {
                log.error("received BGP update from id: {} to id: {}",
                        data.getData().getId(), bgpID);
                return;
            }

            BGP bgp = data.getData();
            BGPListBuilder bgpListBuilder = getBuilder(bgp.getPortId());
            if (mapBgpIdtoBgp.containsKey(bgp.getId())) {
                log.debug("bgp object updated");
                bgpListBuilder.updateBGP(bgp);
            } else {
                log.debug("bgp object added");
                bgpListBuilder.addBGP(bgp);
            }

            mapBgpIdtoBgp.put(bgp.getId(), bgp);
            log.debug("end");
        }

        @Override
        protected Runnable makeRetry() {
            return new Runnable() {
                @Override
                public void run() {
                    bgpMgr.getBGPAsync(bgpID, BgpCallback.this, BgpCallback.this);
                }
            };
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
        public void pathDataChanged(String path) {
            // The BGP node has changed, fetch it again asynchronously.
            bgpMgr.getBGPAsync(bgpID, this, this);
        }
    }

    private class BgpsCallback extends CallbackWithWatcher<Set<UUID>> {
        //TODO(abel) consider having one callback for all Port IDs instead
        //of one callback per each port ID.
        UUID bgpPortID;

        public BgpsCallback(UUID bgpPortID) {
            this.bgpPortID = bgpPortID;
        }

        @Override
        protected String describe() {
            return "BGPs:" + bgpPortID;
        }

        /*
         * DirectoryCallback overrides
         */
        @Override
        public void onSuccess(Result<Set<UUID>> data) {
            log.debug("begin");
            update(data.getData());
            log.debug("end");
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
        public void pathChildrenUpdated(String path) {
            // The list of bgp's for this port has changed. Fetch it again
            // asynchronously.
            bgpMgr.getBgpListAsync(bgpPortID, this, this);
        }

        @Override
        protected Runnable makeRetry() {
            return new Runnable() {
                @Override
                public void run() {
                    bgpMgr.getBgpListAsync(bgpPortID, BgpsCallback.this,
                            BgpsCallback.this);
                }
            };
        }

        /*
         * other methods
         */
        private void update(Set<UUID> bgpIds) {
            log.debug("begin");
            log.debug("bgpIds: {}", bgpIds);
            for (UUID bgpId : mmapPortIdtoBgpIds.get(bgpPortID)) {
                if (!bgpIds.contains(bgpId)) {
                    log.debug("removing unused bgp {} from port {}", bgpId, bgpPortID);
                    mmapPortIdtoBgpIds.remove(bgpPortID, bgpId);
                    mapBgpIdtoPortId.remove(bgpId);
                    getBuilder(bgpPortID).removeBGP(bgpId);
                }
            }

            for (UUID bgpId : bgpIds) {
                if(!mmapPortIdtoBgpIds.containsEntry(bgpPortID, bgpId)) {
                    log.debug("adding new bgp {} to port {}", bgpId, bgpPortID);
                    mmapPortIdtoBgpIds.put(bgpPortID, bgpId);
                    mapBgpIdtoPortId.put(bgpId, bgpPortID);
                    requestBgp(bgpId);
                }
            }
            log.debug("end");
        }
    }

    private class AdRouteCallback extends
            CallbackWithWatcher<AdRouteZkManager.AdRouteConfig> {

        //TODO(abel) consider having one callback for all AdRoutes instead
        // of one callback per AdRoute.
        UUID adRouteId;

        public AdRouteCallback(UUID adRouteId) {
            this.adRouteId = adRouteId;
        }

        @Override
        protected String describe() {
            return "AdRoute:" + adRouteId;
        }

        /*
        * DirectoryCallback overrides
        */
        @Override
        public void onSuccess(Result<AdRouteZkManager.AdRouteConfig> data) {
            log.debug("AdRouteCallback - begin");
            AdRouteZkManager.AdRouteConfig adRouteConfig = data.getData();
            if (adRouteConfig == null) {
                log.error("adRouteConfig is null");
                return;
            }

            BGPListBuilder bgpListBuilder = getBuilder(mapBgpIdtoPortId.get(adRouteConfig.bgpId));

            if (mmapBgpIdtoAdRouteId.containsValue(adRouteId)) {
                //update not supported for AdRoutes at this moment
            } else {
                bgpListBuilder.addAdvertisedRoute(getAdRoute(adRouteConfig));
                mmapBgpIdtoAdRouteId.put(adRouteConfig.bgpId, adRouteId);
                mapAdRouteIdtoAdRouteConfig.put(adRouteId, adRouteConfig);
            }
        }

        /*
        * TypedWatcher overrides
        */
        @Override
        public void pathDeleted(String path) {
            // taken care by AdRoutesCallback
        }

        @Override
        public void pathDataChanged(String path) {
            // The AdRoute node has changed, fetch it again asynchronously.
            log.debug("AdRouteCallback - begin");
            adRouteMgr.getAsync(adRouteId, this, this);
        }

        @Override
        protected Runnable makeRetry() {
            return new Runnable() {
                @Override
                public void run() {
                    adRouteMgr.getAsync(adRouteId,
                            AdRouteCallback.this, AdRouteCallback.this);
                }
            };
        }
    }

    private class AdRoutesCallback extends CallbackWithWatcher<Set<UUID>> {

        //TODO(abel) consider having one callback for all BGP IDs, instead
        // of one callback per BGP.
        UUID bgpID;

        public AdRoutesCallback(UUID bgpID) {
            this.bgpID = bgpID;
        }

        @Override
        protected String describe() {
            return "AdRoutes:" + bgpID;
        }

        /*
         * DirectoryCallback overrides
         */
        @Override
        public void onSuccess(Result<Set<UUID>> data) {
            log.debug("AdRoutesCallback - begin");
            update(data.getData());
        }

        /*
         * TypedWatcher overrides
         */

        @Override
        public void pathChildrenUpdated(String path) {
            log.debug("AdRoutesCallback - begin");
            adRouteMgr.getAdRouteListAsync(bgpID, this, this);
        }

        @Override
        protected Runnable makeRetry() {
            return new Runnable() {
                @Override
                public void run() {
                    adRouteMgr.getAdRouteListAsync(bgpID,
                            AdRoutesCallback.this, AdRoutesCallback.this);
                }
            };
        }

        /*
         * other methods
         */
        private void update(Set<UUID> adRouteIds) {
            log.debug("AdRoutesCallback - begin");
            log.debug("AdRoutesCallback - Received {} routes", adRouteIds.size());
            for (UUID adRouteId : mmapBgpIdtoAdRouteId.get(bgpID)) {
                if (!adRouteIds.contains(adRouteId)) {
                    log.debug("deleting unused route: {}", adRouteId);
                    mmapBgpIdtoAdRouteId.remove(bgpID, adRouteId);

                    UUID portId = mapBgpIdtoPortId.get(bgpID);

                    AdRouteZkManager.AdRouteConfig adRouteConfig =
                            mapAdRouteIdtoAdRouteConfig.remove(adRouteId);
                    getBuilder(portId).removeAdvertisedRoute(getAdRoute(adRouteConfig));
                }
            }

            for (UUID adRouteId : adRouteIds) {
                if (!mmapBgpIdtoAdRouteId.containsValue(adRouteId)) {
                    log.debug("adding new route: {}", adRouteId);
                    requestAdRoute(adRouteId);
               }
            }
        }
    }
}
