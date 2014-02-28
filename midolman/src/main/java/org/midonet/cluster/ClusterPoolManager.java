/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
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
import org.midonet.cluster.data.Converter;
import org.midonet.cluster.data.l4lb.Pool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.cluster.data.l4lb.PoolMember;
import org.midonet.midolman.state.zkManagers.PoolZkManager;
import org.midonet.midolman.state.zkManagers.PoolZkManager.PoolConfig;
import org.midonet.midolman.state.zkManagers.PoolMemberZkManager;
import org.midonet.midolman.state.zkManagers.PoolMemberZkManager.PoolMemberConfig;
import org.midonet.cluster.client.PoolBuilder;

public class ClusterPoolManager extends ClusterManager<PoolBuilder> {
    private static final Logger log = LoggerFactory
            .getLogger(ClusterPoolManager.class);

    @Inject
    PoolZkManager poolZkMgr;

    @Inject
    PoolMemberZkManager poolMemberZkMgr;

    private Map<UUID, Map<UUID, PoolMember>> poolIdToPoolMemberMap =
            new HashMap<>();
    private Map<UUID, Set<UUID>> poolToPoolMemberIds =
            new HashMap<>();
    private Multimap<UUID, UUID> poolToMissingPoolMemberIds =
            HashMultimap.create();

    @Override
    protected void getConfig(UUID poolId) {
        if (poolIdToPoolMemberMap.containsKey(poolId)) {
            log.error("Trying to request the same Pool {}.", poolId);
            return;
        }
        poolIdToPoolMemberMap.put(poolId, new HashMap<UUID, PoolMember>());
        PoolMemberListCallback poolMemberListCB = new PoolMemberListCallback(poolId);
        poolZkMgr.getPoolMemberIdListAsync(poolId, poolMemberListCB, poolMemberListCB);

        PoolConfigCallback poolConfigCB = new PoolConfigCallback(poolId);
        poolZkMgr.getPoolAsync(poolId, poolConfigCB, poolConfigCB);
    }

    private void requestPoolMember(UUID poolMemberID) {
        PoolMemberCallback poolMemberCallback = new PoolMemberCallback(poolMemberID);
        poolMemberZkMgr.getPoolMemberAsync(poolMemberID, poolMemberCallback, poolMemberCallback);
    }

    private class PoolConfigCallback extends CallbackWithWatcher<PoolConfig> {
        private UUID poolId;

        private PoolConfigCallback(UUID poolId) {
            this.poolId = poolId;
        }

        @Override
        protected String describe() {
            return "PoolConfig:" + poolId;
        }

        @Override
        public void onSuccess(Result<PoolConfig> data) {
            Pool pool = Converter.fromPoolConfig(data.getData());
            pool.setId(poolId);
            getBuilder(poolId).setPoolConfig(pool);
        }

        @Override
        public void pathDataChanged(String path) {
            poolZkMgr.getPoolAsync(poolId, this, this);
        }

        @Override
        protected Runnable makeRetry() {
            return new Runnable() {
                @Override
                public void run() {
                    poolZkMgr.getPoolAsync(poolId,
                            PoolConfigCallback.this, PoolConfigCallback.this);
                }
            };
        }
    }

    private class PoolMemberListCallback extends CallbackWithWatcher<Set<UUID>> {
        private UUID poolId;

        private PoolMemberListCallback(UUID PoolId) {
            this.poolId = PoolId;
        }

        @Override
        protected String describe() {
            return "PoolMemberList:" + poolId;
        }

        @Override
        public void onSuccess(Result<Set<UUID>> data) {
            // This is a set of the UUIDs of current PoolMembers
            Set<UUID> curPoolMemberIds = data.getData();

            // UUID to actual PoolMember for each PoolMember in Pool
            Map<UUID, PoolMember> poolMemberMap = poolIdToPoolMemberMap.get(poolId);

            poolToPoolMemberIds.put(poolId, curPoolMemberIds);

            // Set of old PoolMember IDs from Pool
            Set<UUID> oldPoolMemberIds = poolMemberMap.keySet();

            // Copy current PoolMembers
            Set<UUID> poolMembersToRequest = new HashSet<UUID>(curPoolMemberIds);

            // If the new set tells us a PoolMember disappeared,
            // remove it from the Pool's PoolMember id -> PoolMember info map
            // Also remove from poolMembersToRequest so that only the vips we
            // need are left
            Iterator<UUID> poolMemberIter = oldPoolMemberIds.iterator();
            while (poolMemberIter.hasNext()) {
                if (!poolMembersToRequest.remove(poolMemberIter.next()))
                    poolMemberIter.remove();
            }

            // If we have all the PoolMembers in the new set, we're
            // ready to call the PoolBuilder
            if (poolMembersToRequest.isEmpty()) {
                getBuilder(poolId).setPoolMembers(poolMemberMap);
                return;
            }

            // Otherwise, we have to fetch some PoolMembers.
            for(UUID poolMemberId : poolMembersToRequest) {
                poolToMissingPoolMemberIds.put(poolId, poolMemberId);
            }

            // We do this in two passes (mark all missing, request all
            // missing) to avoid race condition where a pool member request
            // returns before we've marked all missing Vpool members
            for(UUID poolMemberId : poolMembersToRequest) {
                requestPoolMember(poolMemberId);
            }
        }

        @Override
        public void pathChildrenUpdated(String path) {
            poolZkMgr.getPoolMemberIdListAsync(poolId, this, this);
        }

        @Override
        protected Runnable makeRetry() {
            return new Runnable() {
                @Override
                public void run() {
                    poolZkMgr.getPoolMemberIdListAsync(poolId,
                            PoolMemberListCallback.this, PoolMemberListCallback.this);
                }
            };
        }

    }


    private class PoolMemberCallback
            extends CallbackWithWatcher<PoolMemberConfig> {
        private UUID poolMemberId;

        private PoolMemberCallback(UUID PoolMemberId) {
            this.poolMemberId = PoolMemberId;
        }

        @Override
        protected String describe() {
            return "PoolMember:" + poolMemberId;
        }

        @Override
        public void onSuccess(Result<PoolMemberConfig> data) {
            PoolMember poolMember = Converter.fromPoolMemberConfig(data.getData());
            poolMember.setId(poolMemberId);

            Collection<UUID> missingPoolMemberIds =
                    poolToMissingPoolMemberIds.get(poolMember.getPoolId());
            Set<UUID> poolMemberIds = poolToPoolMemberIds.get(poolMember.getPoolId());
            // Does the Pool still care about this poolMember?
            if (poolMemberIds == null || !poolMemberIds.contains(poolMemberId))
                return;
            missingPoolMemberIds.remove(poolMemberId);
            Map<UUID, PoolMember> poolMemberMap = poolIdToPoolMemberMap.get(
                    poolMember.getPoolId());

            poolMemberMap.put(poolMemberId, poolMember);

            if ((missingPoolMemberIds.size() == 0)) {
                getBuilder(poolMember.getPoolId()).setPoolMembers(poolMemberMap);
            }
        }

        @Override
        public void pathDataChanged(String path) {
            poolMemberZkMgr.getPoolMemberAsync(poolMemberId, this, this);
        }

        @Override
        protected Runnable makeRetry() {
            return new Runnable() {
                @Override
                public void run() {
                    poolMemberZkMgr.getPoolMemberAsync(poolMemberId,
                            PoolMemberCallback.this, PoolMemberCallback.this);
                }
            };
        }
    }

}
