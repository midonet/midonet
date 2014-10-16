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
package org.midonet.cluster

import java.util.{Map => JMap, UUID}
import scala.collection.JavaConverters._
import scala.collection.mutable.{HashMap => MMap}
import scala.collection.immutable.{HashMap => IMap}

import com.google.inject.Inject
import org.slf4j.{LoggerFactory, Logger}

import org.midonet.cluster.client.PoolHealthMonitorMapBuilder
import org.midonet.cluster.data.l4lb.Pool
import org.midonet.midolman.state.zkManagers.PoolHealthMonitorZkManager.PoolHealthMonitorConfig
import org.midonet.midolman.state.zkManagers.PoolHealthMonitorZkManager

class ClusterPoolHealthMonitorMapManager
        extends ClusterManager[PoolHealthMonitorMapBuilder]{

    private final val log: Logger =
        LoggerFactory.getLogger(classOf[ClusterPoolHealthMonitorMapManager])

    @Inject
    private[this] val poolHealthMonitorZkManager: PoolHealthMonitorZkManager
        = null
    private val watchedPoolIdToHmId = new MMap[UUID, UUID]()
    private val poolIdToMapConfig =
        new MMap[UUID, PoolHealthMonitorConfig]()

    /*
     * Callback to retrieve the mapping of health monitors to pools.
     */
    private class MappingCallBack(
            private val builder: PoolHealthMonitorMapBuilder)
            extends CallbackWithWatcher[JMap[UUID, UUID]] {

        protected def describe(): String = "Pool Health Monitor Mappings"

        protected def makeRetry(): Runnable = {
            new Runnable {
                def run(): Unit = {
                    log.debug("Retrying to get pool health monitor mappings")
                    poolHealthMonitorZkManager
                        .getPoolHealthMonitorMappingsAsync(
                           MappingCallBack.this, MappingCallBack.this)
                }
            }
        }

        def processNewMap(newMap: JMap[UUID, UUID]): MMap[UUID, UUID] = {
            val requestsPendingMap =  MMap[UUID, UUID]()
            val allKeys = newMap.keySet.asScala union watchedPoolIdToHmId.keySet
            allKeys foreach (poolId =>
                (newMap.asScala.get(poolId),
                        watchedPoolIdToHmId.get(poolId)) match {
                    case (Some(newHmId), Some(oldHmId)) if newHmId != oldHmId =>
                        // This is a change in health monitors.
                        poolIdToMapConfig remove poolId
                        requestsPendingMap put (poolId, newHmId)
                       // requestData(poolId, newHmId)
                    case (Some(newHmId), None) => // new association
                        watchedPoolIdToHmId put (poolId, newHmId)
                        requestsPendingMap put (poolId, newHmId)
                    case (None, Some(oldHmId)) => // removed association
                        watchedPoolIdToHmId remove poolId
                        poolIdToMapConfig remove poolId
                    case _ => //Nothing has changed, so safe to ignore.
                }
            )
            requestsPendingMap
        }

        def onSuccess(cfg: JMap[UUID, UUID]): Unit = {
            if (cfg != null) {
                log.debug("Got pool health monitor mappings")
                val requestsPending = processNewMap(cfg)
                if (watchedPoolIdToHmId.size == poolIdToMapConfig.size) {
                    // If we still have some missing data, it will be
                    // retrieved when the data comes in via the
                    // GetConfDataCallback. The build will happen then.
                    builder.set(IMap(poolIdToMapConfig.toSeq:_*))
                    builder.build()
                }
                requestsPending.foreach(kv => requestData(kv._1, kv._2))
            } else {
                log.warn("null pool health monitor mapping returned from ZK")
            }
        }

        override def pathChildrenUpdated(path: String) {
            poolHealthMonitorZkManager
                .getPoolHealthMonitorMappingsAsync(this, this)
        }
    }

    private def requestData(poolId: UUID, hmId: UUID) {
        val cb = new GetConfDataCallBack(poolId, hmId)
        poolHealthMonitorZkManager.getPoolHealthMonitorConfDataAsync(
            poolId, hmId, cb, cb)
    }

    /*
     * Callback class for retrieving information on the individual entries
     * in the Pool -- HealthMonitor mapping.
     */
    private class GetConfDataCallBack(val poolId: UUID, val hmId: UUID)
            extends CallbackWithWatcher[PoolHealthMonitorConfig] {
        override protected def describe(): String = "Pool Health Monitor Mappings"

        override protected def makeRetry: Runnable = {
            new Runnable {
                def run() {
                    poolHealthMonitorZkManager
                        .getPoolHealthMonitorConfDataAsync(poolId, hmId,
                            GetConfDataCallBack.this, GetConfDataCallBack.this)
                }
            }
        }

        def onSuccess(cfg: PoolHealthMonitorConfig): Unit = {
             if (!watchedPoolIdToHmId.contains(poolId)) {
                 // This is just a late change notification. We stopped
                 // watching this already.
                 return
             }

            poolIdToMapConfig.put(poolId, cfg)
             if (watchedPoolIdToHmId.size == poolIdToMapConfig.size) {
                 val builder: PoolHealthMonitorMapBuilder =
                     getBuilder(Pool.POOL_HEALTH_MONITOR_MAP_KEY)
                 builder.set(IMap(poolIdToMapConfig.toSeq:_*))
                 builder.build()
             }
        }

        override def pathDataChanged(path: String) {
            poolHealthMonitorZkManager.getPoolHealthMonitorConfDataAsync(
                poolId, hmId, this, this)
        }

        override def pathDeleted(path: String) {
            // Do nothing. when the path is deleted, it is handled by the
            // pathChildrenUpdated handler in MappingCallBack
        }
    }

    override protected def getConfig(id: UUID): Unit = {
        log.debug("pool health monitor map getConfig {}", id)
        getBuilder(id) match {
            case builder: PoolHealthMonitorMapBuilder =>
                val cb = new MappingCallBack(builder)
                poolHealthMonitorZkManager
                    .getPoolHealthMonitorMappingsAsync(cb, cb)
            case _ =>
                log.error("Builder not found for health monitor pool " +
                          "map {}.", id)
        }
    }
}
