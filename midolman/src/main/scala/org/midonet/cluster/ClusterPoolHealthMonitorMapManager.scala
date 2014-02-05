/*
 * Copyright (c) 2014 Midokura Pte.Ltd.
 */
package org.midonet.cluster


import org.midonet.cluster.client.PoolHealthMonitorMapBuilder
import org.midonet.cluster.data.l4lb.Pool
import org.midonet.midolman.state.DirectoryCallback.Result
import org.midonet.midolman.state.zkManagers.PoolZkManager.PoolHealthMonitorMappingConfig
import org.midonet.midolman.state.zkManagers.PoolZkManager

import com.google.inject.Inject
import java.util.{Map => JMap, UUID}
import org.slf4j.{LoggerFactory, Logger}
import scala.collection.JavaConverters._
import scala.collection.mutable.{HashMap => MMap}


class ClusterPoolHealthMonitorMapManager
        extends ClusterManager[PoolHealthMonitorMapBuilder]{

    private final val log: Logger =
        LoggerFactory.getLogger(classOf[ClusterPoolHealthMonitorMapManager])

    @Inject
    private[this] val poolZkManager: PoolZkManager = null
    private val watchedPoolIdToHmId = new MMap[UUID, UUID]()
    private val poolIdToMapConfig =
        new MMap[UUID, PoolHealthMonitorMappingConfig]()

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
                    poolZkManager.getPoolHealthMonitorMappingsAsync(
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

        def onSuccess(res: Result[JMap[UUID, UUID]]): Unit = {
            log.debug("Got pool health monitor mappings")
            res.getData match {
                case cfg: JMap[UUID, UUID] =>
                    val requestsPending = processNewMap(cfg)
                    if (watchedPoolIdToHmId.size == poolIdToMapConfig.size) {
                        // If we still have some missing data, it will be
                        // retrieved when the data comes in via the
                        // GetConfDataCallback. The build will happen then.
                        builder.set(poolIdToMapConfig)
                        builder.build()
                    }
                    requestsPending.foreach(kv => requestData(kv._1, kv._2))
                case _ =>
                    log.warn("Bad pool health monitor mapping returned from ZK")
            }
        }

        override def pathChildrenUpdated(path: String) {
            poolZkManager.getPoolHealthMonitorMappingsAsync(this, this)
        }
    }

    private def requestData(poolId: UUID, hmId: UUID) {
        val cb = new GetConfDataCallBack(poolId, hmId)
        poolZkManager.getPoolHealthMonitorConfDataAsync(
            poolId, hmId, cb, cb)
    }

    /*
     * Callback class for retrieving information on the individual entries
     * in the Pool -- HealthMonitor mapping.
     */
    private class GetConfDataCallBack(val poolId: UUID, val hmId: UUID)
            extends CallbackWithWatcher[PoolHealthMonitorMappingConfig] {
        override protected def describe(): String = "Pool Health Monitor Mappings"

        override protected def makeRetry: Runnable = {
            new Runnable {
                def run() {
                    poolZkManager.getPoolHealthMonitorConfDataAsync(
                        poolId, hmId, GetConfDataCallBack.this,
                        GetConfDataCallBack.this)
                }
            }
        }

        def onSuccess(res: Result[PoolHealthMonitorMappingConfig]): Unit = {
             if (!watchedPoolIdToHmId.contains(poolId)) {
                 // This is just a late change notification. We stopped
                 // watching this already.
                 return
             }

            poolIdToMapConfig.put(poolId, res.getData)
             if (watchedPoolIdToHmId.size == poolIdToMapConfig.size) {
                 val builder: PoolHealthMonitorMapBuilder =
                     getBuilder(Pool.POOL_HEALTH_MONITOR_MAP_KEY)
                 builder.set(poolIdToMapConfig)
                 builder.build()
             }
        }

        override def pathDataChanged(path: String) {
            poolZkManager.getPoolHealthMonitorConfDataAsync(
                poolId, hmId, this, this)
        }
    }

    override protected def getConfig(id: UUID): Unit = {
        log.debug("pool health monitor map getConfig {}", id)
        getBuilder(id) match {
            case builder: PoolHealthMonitorMapBuilder =>
                val cb = new MappingCallBack(builder)
                poolZkManager.getPoolHealthMonitorMappingsAsync(cb, cb)
            case _ =>
                log.error("Builder not found for health monitor pool " +
                          "map {}.", id)
        }
    }
}
