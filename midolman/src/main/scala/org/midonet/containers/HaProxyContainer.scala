/*
 * Copyright 2016 Midokura SARL
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

package org.midonet.containers

import java.util.UUID
import java.util.concurrent.{ExecutorService, ScheduledExecutorService, TimeUnit}

import javax.inject.Named

import scala.concurrent.{Future, Promise}
import scala.util.Try
import scala.util.control.NonFatal

import com.google.inject.Inject

import rx.{Observable, Subscription}

import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.haproxy.HaproxyHelper
import org.midonet.midolman.l4lb._
import org.midonet.midolman.simulation.RouterPort
import org.midonet.midolman.topology.PoolHealthMonitorMapper.PoolHealthMonitorMapKey
import org.midonet.midolman.topology.devices.PoolHealthMonitorMap
import org.midonet.midolman.topology.{ObjectReferenceTracker, VirtualTopology}
import org.midonet.util.functors.{makeAction0, makeAction1, makeFunc1}

object HaProxyContainer {
    val hmSuffix = "_hm"
}

@Container(name = Containers.HAPROXY_CONTAINER, version = 1)
class HaProxyContainer @Inject()(
        @Named("id") id: UUID,
        vt: VirtualTopology,
        @Named("container") containerExecutor: ExecutorService,
        @Named("io") ioExecutor: ScheduledExecutorService,
        config: MidolmanConfig)
    extends ContainerHandler with ContainerCommons {

    override def logSource = "org.midonet.containers.haproxy"
    override def logMark = s"haproxy:$id"

    private val haProxyHelper = new HaproxyHelper()

    private var refsSub: Subscription = _
    private var lbId: UUID = _
    private var ifName: String = _
    private var namespaceName: String = _

    @volatile private var createPromise: Promise[Option[String]] = _

    private val poolHmTracker =
        new ObjectReferenceTracker(vt, classOf[PoolHealthMonitorMap], log)
    private val portTracker =
        new ObjectReferenceTracker(vt, classOf[RouterPort], log)

    private val statusObservable = Observable
        .interval(1, TimeUnit.SECONDS)
        .map[ContainerStatus](makeFunc1(getStatus))

    /**
      * Creates a container for the specified exterior port and service
      * container. The port contains the interface name that the container
      * handler should create, and the method returns a future that completes
      * when the container has been created. Successful future will contain the
      * name of the namespace where the container was created. Failed futures
      * will contain any exception that prevented the handler to spawn the
      * service container.
      */
    override def create(port: ContainerPort): Future[Option[String]] = {
        lbId = port.configurationId
        ifName = port.interfaceName
        namespaceName = HaproxyHelper.namespaceName(lbId.toString)
        portTracker.requestRefs()

        unsubscribeRefs()
        subscribeRefs(port.portId)

        createPromise = Promise()
        createPromise.future
    }

    // Shouldn't happen, so no need to do anything.
    override def updated(port: ContainerPort): Future[Option[String]] = {
        Future.successful(None)
    }

    private def unsubscribeRefs(): Unit =
        if (refsSub != null) {
            refsSub.unsubscribe()
            refsSub = null
            poolHmTracker.requestRefs()
            portTracker.requestRefs()
        }

    private def subscribeRefs(portId: UUID): Unit = {
        assert(refsSub == null)
        poolHmTracker.requestRefs(PoolHealthMonitorMapKey)
        portTracker.requestRefs(portId)
        refsSub = Observable
            .merge(poolHmTracker.refsObservable, portTracker.refsObservable)
            .filter(makeFunc1(areRefsReady))
            .doOnNext(makeAction1(onRefsUpdated))
            .doOnCompleted(makeAction0(onPoolHmMapDeleted()))
            .doOnError(makeAction1(onPoolHmMapError))
            .subscribe()
    }

    private def areRefsReady(ignored: AnyRef): Boolean =
        poolHmTracker.areRefsReady && portTracker.areRefsReady

    private def onRefsUpdated(ignored: AnyRef): Unit = {
        val lbCfg = toLbV2Config(poolHmTracker.currentRefs.head._2)
        if (createPromise != null) {
            // Still need to deploy.
            val p = portTracker.currentRefs.head._2
            createPromise.tryComplete(Try {
                haProxyHelper.deploy(
                    lbCfg, ifName, p.portMac.toString,
                    containerPortAddress(p.portAddress4).toString,
                    routerPortAddress(p.portAddress4).toString)
                Some(namespaceName)
            })
        } else {
            // Already deployed. Just restart.
            haProxyHelper.restart(lbCfg)
        }
    }

    private def toLbV2Config(phmMap: PoolHealthMonitorMap)
    : LoadBalancerV2Config = {
        val phms = phmMap.mappings.toStream.filter {
            case (poolId, phm) => phm.loadBalancer.id == lbId
        }

        val listenerCfgs = for((poolId, phm) <- phms; vip <- phm.vips) yield {
            ListenerV2Config(vip.id, vip.adminStateUp,
                             vip.protocolPort, vip.poolId)
        }

        val poolCfgs = for ((poolId, phm) <- phms) yield {
            val members = for (pm <- phm.poolMembers.toStream)
                yield MemberV2Config(pm.id, pm.adminStateUp,
                                     pm.address.toString, pm.protocolPort)

            val hm = phm.healthMonitor
            val hmCfg = HealthMonitorV2Config(hm.id, hm.adminStateUp, hm.delay,
                                              hm.timeout, hm.maxRetries)

            PoolV2Config(poolId, adminStateUp = true, members.toSet, hmCfg)
        }

        LoadBalancerV2Config(lbId, listenerCfgs.toSet, poolCfgs.toSet,
                             phms.head._2.loadBalancer.adminStateUp)
    }

    private def onPoolHmMapDeleted(): Unit = {
        log.error("PoolHealthMonitorMap observable completed. Not expected.")
    }

    private def onPoolHmMapError(t: Throwable): Unit = {
        log.error("PoolHealthMonitorMap observable raised error.", t)
    }

    /**
      * Deletes the container for the specified exterior port and namespace
      * information. The method returns a future that completes when the
      * container has been deleted.
      */
    override def delete(): Future[Unit] = {
        cleanup(namespaceName)
    }

    /**
      * Cleans-up the container for the specified container name. The method
      * returns a future that completes when the container has been cleaned.
      */
    override def cleanup(name: String): Future[Unit] = {
        unsubscribeRefs()
        Future.fromTry(Try(haProxyHelper.undeploy(name, ifName)))
    }

    private def getStatus(tick: java.lang.Long): ContainerStatus = {
        import org.midonet.cluster.models.State.ContainerStatus.Code
        val (code, msg) = if (createPromise != null) {
            (Code.STARTING, "Starting")
        } else if (refsSub == null) {
            (Code.STOPPED, "Stopped")
        } else {
            try {
                // TODO: Use cheaper status check method once implemented.
                haProxyHelper.getStatus()
                (Code.RUNNING, "Running")
            } catch {
                case NonFatal(t) =>
                    log.error("Could not get HaProxy status", t)
                    (Code.ERROR, t.getMessage)
            }
        }
        ContainerHealth(code, namespaceName, msg)
    }

    // TODO: Only emits container health updates, not container op updates.
    override def status: Observable[ContainerStatus] = statusObservable

}
