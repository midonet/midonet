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

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.google.common.base.Objects;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.google.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.cluster.client.BGPListBuilder;
import org.midonet.cluster.data.AdRoute;
import org.midonet.cluster.data.BGP;
import org.midonet.midolman.state.zkManagers.AdRouteZkManager;
import org.midonet.midolman.state.zkManagers.BgpZkManager;

public class ClusterBgpManager extends ClusterManager<BGPListBuilder> {

    private static final Logger log = LoggerFactory
            .getLogger(ClusterBgpManager.class);

    @Inject
    BgpZkManager bgpMgr;

    @Inject
    AdRouteZkManager adRouteMgr;

    private Multimap<UUID, UUID> portIdtoBgpIds = HashMultimap.create();
    private Map<UUID, UUID> bgpIdtoPortId = new HashMap<>();
    private Set<UUID> requestedBgps = new HashSet<>();
    private Multimap<UUID, UUID> bgpIdtoAdRouteId = HashMultimap.create();
    private Map<UUID, AdRouteZkManager.AdRouteConfig> adRouteIdtoAdRouteConfig =
            new HashMap<>();

    @Override
    protected void getConfig(final UUID bgpPortID) {
        requestBgps(bgpPortID);
    }

    /*
     * In order to decouple Zk from the Builder, we need to convert
     * AdRouteConfig to AdRoute.
     */
    private static AdRoute getAdRoute(UUID id,
                                      AdRouteZkManager.AdRouteConfig adRouteConfig) {
        return new AdRoute()
                .setId(id)
                .setBgpId(adRouteConfig.bgpId)
                .setNwPrefix(adRouteConfig.nwPrefix)
                .setPrefixLength(adRouteConfig.prefixLength);
    }

    private void requestBgp(UUID bgpID) {
        log.debug("requesting bgp with id {}", bgpID);

        BgpCallback bgpCallback = new BgpCallback(bgpID);
        bgpMgr.getBGPAsync(bgpID, bgpCallback, bgpCallback);

        AdRoutesCallback adRoutesCallback = new AdRoutesCallback(bgpID);
        adRouteMgr.getAdRouteListAsync(bgpID, adRoutesCallback, adRoutesCallback);
    }

    private void requestBgps(UUID bgpPortID) {
        log.debug("requesting list of bgp's for port {}", bgpPortID);

        if (portIdtoBgpIds.containsKey(bgpPortID)) {
            log.error("trying to request BGPs more than once for this port ID: " + bgpPortID);
            return;
        }

        BgpsCallback bgpsCallback = new BgpsCallback(bgpPortID);
        bgpMgr.getBgpListAsync(bgpPortID, bgpsCallback, bgpsCallback);
    }

    private void requestAdRoute(UUID adRouteId) {
        log.debug("requesting advertised route {}", adRouteId);
        if (bgpIdtoAdRouteId.containsValue(adRouteId)) {
            log.error("requestAdRoute is only for creations, not for updates.");
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
        public void onSuccess(BGP bgp) {
            // We shall receive only updates for this BGP object
            if (!Objects.equal(bgp.getId(), bgpID)) {
                log.error("received BGP update from id: {} to id: {}",
                        bgp.getId(), bgpID);
                return;
            }

            BGPListBuilder bgpListBuilder = getBuilder(bgp.getPortId());
            if (requestedBgps.contains(bgp.getId())) {
                log.debug("bgp object updated {}", bgp.getId());
                bgpListBuilder.updateBGP(bgp);
            } else {
                log.debug("bgp object added {}", bgp.getId());
                bgpListBuilder.addBGP(bgp);
                requestedBgps.add(bgp.getId());
            }
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
        public void onSuccess(Set<UUID> uuids) {
            update(uuids);
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
            log.debug("update bgpIds: {}", bgpIds);
            for (UUID bgpId : portIdtoBgpIds.get(bgpPortID)) {
                if (!bgpIds.contains(bgpId)) {
                    log.debug("removing unused bgp {} from port {}", bgpId, bgpPortID);
                    bgpIdtoPortId.remove(bgpId);
                    getBuilder(bgpPortID).removeBGP(bgpId);
                }
            }

            for (UUID bgpId : bgpIds) {
                if (!portIdtoBgpIds.containsEntry(bgpPortID, bgpId)) {
                    log.debug("adding new bgp {} to port {}", bgpId, bgpPortID);
                    bgpIdtoPortId.put(bgpId, bgpPortID);
                    requestBgp(bgpId);
                }
            }

            portIdtoBgpIds.replaceValues(bgpPortID, bgpIds);
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
        public void onSuccess(AdRouteZkManager.AdRouteConfig adRouteConfig) {
            if (adRouteConfig == null) {
                log.error("adRouteConfig is null");
                return;
            }

            BGPListBuilder bgpListBuilder = getBuilder(bgpIdtoPortId.get(adRouteConfig.bgpId));

            bgpListBuilder.addAdvertisedRoute(getAdRoute(adRouteId, adRouteConfig));
            adRouteIdtoAdRouteConfig.put(adRouteId, adRouteConfig);
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
            log.debug("fetching advertised route {}", adRouteId);
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
        public void onSuccess(Set<UUID> uuids) {
            update(uuids);
        }

        /*
         * TypedWatcher overrides
         */

        @Override
        public void pathChildrenUpdated(String path) {
            log.debug("getting advertised routes list for {}", bgpID);
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
            log.debug("Received {} advertised routes", adRouteIds.size());
            for (UUID adRouteId : bgpIdtoAdRouteId.get(bgpID)) {
                if (!adRouteIds.contains(adRouteId)) {
                    log.debug("deleting unused advertised route: {}", adRouteId);

                    UUID portId = bgpIdtoPortId.get(bgpID);
                    AdRouteZkManager.AdRouteConfig adRouteConfig =
                            adRouteIdtoAdRouteConfig.remove(adRouteId);
                    getBuilder(portId).removeAdvertisedRoute(
                                    getAdRoute(adRouteId, adRouteConfig));
                }
            }

            for (UUID adRouteId : adRouteIds) {
                if (!bgpIdtoAdRouteId.containsValue(adRouteId)) {
                    log.debug("adding new advertised route: {}", adRouteId);
                    requestAdRoute(adRouteId);
               }
            }

            bgpIdtoAdRouteId.replaceValues(bgpID, adRouteIds);
        }
    }
}
