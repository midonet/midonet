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

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.google.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.cluster.client.LoadBalancerBuilder;
import org.midonet.cluster.data.Converter;
import org.midonet.cluster.data.l4lb.VIP;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.zkManagers.LoadBalancerZkManager;
import org.midonet.midolman.state.zkManagers.VipZkManager;

public class ClusterLoadBalancerManager
        extends ClusterManager<LoadBalancerBuilder> {
    private static final Logger log = LoggerFactory
            .getLogger(ClusterLoadBalancerManager.class);

    @Inject
    LoadBalancerZkManager loadBalancerZkMgr;

    @Inject
    VipZkManager vipZkMgr;

    private Map<UUID, Map<UUID, VIP>> loadBalancerIdToVipMap = new HashMap<>();
    private Map<UUID, Set<UUID>> loadBalancerToVipIds = new HashMap<>();
    private Multimap<UUID, UUID> loadBalancerToMissingVipIds =
            HashMultimap.create();

    @Override
    protected void getConfig(UUID id) {
        getLoadBalancerConf(id, false);
    }

    protected void getLoadBalancerConf(UUID loadBalancerId,
                                       final boolean isUpdate) {

        log.debug("Updating configuration for load balancer {}",
                loadBalancerId);
        LoadBalancerBuilder builder = getBuilder(loadBalancerId);

        if (builder == null) {
            log.error("Null builder for load balancer {}",
                    loadBalancerId.toString());
            return;
        }

        LoadBalancerZkManager.LoadBalancerConfig lbCfg = null;

        try {
            if (!isUpdate) {
                // Set watchers on the VIP list
                loadBalancerIdToVipMap.put(loadBalancerId,
                        new HashMap<UUID, VIP>());
                VipListCallback vipListCB = new VipListCallback(loadBalancerId);
                loadBalancerZkMgr.getVipIdListAsync(loadBalancerId, vipListCB,
                    vipListCB);
            }
            lbCfg = loadBalancerZkMgr.get(loadBalancerId,
                    watchLoadBalancer(loadBalancerId, true));
        } catch (StateAccessException e) {
            log.warn("Cannot retrieve the configuration for loadBalancer {}",
                    loadBalancerId, e);
            connectionWatcher.handleError(
                    loadBalancerId.toString(),
                    watchLoadBalancer(loadBalancerId, true), e);
            return;
        } catch (SerializationException e) {
            log.error("Could not deserialize loadBalancer config: {}",
                    loadBalancerId, e);
            return;
        }

        if (lbCfg == null) {
            log.warn("Received null loadBalancer config for {}", loadBalancerId);
            return;
        }

        // Set any values from the lb config
        builder.setAdminStateUp(lbCfg.adminStateUp);
        builder.setRouterId(lbCfg.routerId);

        // Only build if we're not waiting for VIPs
        Collection<UUID> missingVipIds =
                loadBalancerToMissingVipIds.get(loadBalancerId);
        if (missingVipIds.size() == 0) {
             builder.build();
        }
    }

    Runnable watchLoadBalancer(final UUID id, final boolean isUpdate) {
        return new Runnable() {
            @Override
            public void run() {
                getLoadBalancerConf(id, isUpdate);
            }
        };
    }

    private void requestVip(UUID vipID) {
        VipCallback vipCallback = new VipCallback(vipID);
        vipZkMgr.getAsync(vipID, vipCallback, vipCallback);
    }

    private class VipListCallback extends CallbackWithWatcher<Set<UUID>> {
        private UUID loadBalancerId;

        private VipListCallback(UUID loadBalancerId) {
            this.loadBalancerId = loadBalancerId;
        }

        @Override
        protected String describe() {
            return "VipList:" + loadBalancerId;
        }

        @Override
        public void onSuccess(Set<UUID> curVipIds) {
            // curVipIds is a set of the UUIDs of current VIPs

            // UUID to actual VIP for each vip in LoadBalancer
            Map<UUID, VIP> vipMap = loadBalancerIdToVipMap.get(loadBalancerId);

            // If null, we no longer care about this loadBalancerId.
            if (null == vipMap) {
                loadBalancerToVipIds.remove(loadBalancerId);
                return;
            } else {
                loadBalancerToVipIds.put(loadBalancerId, curVipIds);
            }

            // Set of old vip IDs from LoadBalancer
            Set<UUID> oldVipIds = vipMap.keySet();

            // Copy current VIPs
            Set<UUID> vipsToRequest = new HashSet<UUID>(curVipIds);

            // If the new set tells us a vip disappeared,
            // remove it from the LoadBalancer's vip id -> vip info map
            // Also remove from vipsToRequest so that only the vips we
            // need are left
            Iterator<UUID> vipIter = oldVipIds.iterator();
            while (vipIter.hasNext()) {
                if (!vipsToRequest.remove(vipIter.next()))
                    vipIter.remove();
            }

            // If we have all the vips in the new ordered list, we're
            // ready to call the LoadBalancerBuilder
            if (vipsToRequest.isEmpty()) {
                LoadBalancerBuilder builder = getBuilder(loadBalancerId);
                builder.setVips(vipMap);
                return;
            }

            // Otherwise, we have to fetch some vips.
            for (UUID vipId : vipsToRequest) {
                loadBalancerToMissingVipIds.put(loadBalancerId, vipId);
            }

            // We do this in two passes (mark all missing VIPs, request all
            // missing VIPs) to avoid race condition where a VIP request returns
            // before we've marked all missing VIPs
            for(UUID vipId : vipsToRequest) {
                requestVip(vipId);
            }

        }

        @Override
        public void pathChildrenUpdated(String path) {
            loadBalancerZkMgr.getVipIdListAsync(loadBalancerId, this, this);
        }

        @Override
        protected Runnable makeRetry() {
            return new Runnable() {
                @Override
                public void run() {
                    loadBalancerZkMgr.getVipIdListAsync(loadBalancerId,
                            VipListCallback.this, VipListCallback.this);
                }
            };
        }

    }


    private class VipCallback extends CallbackWithWatcher<VipZkManager.VipConfig> {
        private UUID vipId;

        private VipCallback(UUID vipId) {
            this.vipId = vipId;
        }

        @Override
        protected String describe() {
            return "Vip:" + vipId;
        }

        @Override
        public void onSuccess(VipZkManager.VipConfig vipConfig) {
            VIP vip = Converter.fromVipConfig(vipConfig);
            vip.setId(vipId);

            Collection<UUID> missingVipIds =
                loadBalancerToMissingVipIds.get(vip.getLoadBalancerId());
            Set<UUID> vipIds = loadBalancerToVipIds.get(vip.getLoadBalancerId());
            // Does the LoadBalancer still care about this vip?
            if (vipIds == null || !vipIds.contains(vipId))
                return;
            missingVipIds.remove(vipId);
            Map<UUID, VIP> vipMap = loadBalancerIdToVipMap.get(vip.getLoadBalancerId());

            vipMap.put(vipId, vip);

            if (missingVipIds.size() == 0) {
                LoadBalancerBuilder builder = getBuilder(vip.getLoadBalancerId());
                builder.setVips(vipMap);
            }
        }

        @Override
        public void pathDataChanged(String path) {
            vipZkMgr.getAsync(vipId, this, this);
        }

        @Override
        protected Runnable makeRetry() {
            return new Runnable() {
                @Override
                public void run() {
                    vipZkMgr.getAsync(vipId,
                            VipCallback.this, VipCallback.this);
                }
            };
        }
    }

}
