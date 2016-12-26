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
import java.util.concurrent.{ExecutorService, TimeUnit}

import javax.inject.Named

import scala.collection.JavaConverters._
import scala.concurrent.{Future, Promise}
import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}

import com.google.inject.Inject

import rx.schedulers.Schedulers
import rx.subjects.PublishSubject
import rx.{Observable, Subscription}

import org.midonet.cluster.data.storage.NotFoundException
import org.midonet.cluster.models.Commons.LBStatus
import org.midonet.cluster.models.State.ContainerStatus.Code._
import org.midonet.cluster.models.Topology.PoolMember
import org.midonet.cluster.services.MidonetBackend.StatusKey
import org.midonet.midolman.haproxy.HaproxyHelper
import org.midonet.midolman.l4lb._
import org.midonet.midolman.simulation.RouterPort
import org.midonet.midolman.topology.PoolHealthMonitorMapper.PoolHealthMonitorMapKey
import org.midonet.midolman.topology.VirtualTopology
import org.midonet.midolman.topology.VirtualTopology.Device
import org.midonet.midolman.topology.devices.PoolHealthMonitorMap
import org.midonet.util.functors.{makeAction0, makeAction1, makeFunc1}

object HaProxyContainer {
    val hmSuffix = "_hm"
}

@Container(name = Containers.HAPROXY_CONTAINER, version = 1)
class HaProxyContainer @Inject()(
        @Named("id") id: UUID,
        vt: VirtualTopology,
        @Named("container") containerExecutor: ExecutorService)
    extends ContainerHandler with ContainerCommons {

    override def logSource = "org.midonet.containers.haproxy"
    override def logMark = s"haproxy:$id"

    protected val haProxyHelper = new HaproxyHelper()

    private var lbId: UUID = _
    private var ifaceName: String = _
    private var namespaceName: String = _

    // Promise for the result of a create() call.
    private var createPromise: Promise[Option[String]] = _

    private var poolHmMapObservable: Observable[PoolHealthMonitorMap] = _
    private var portObservable: Observable[RouterPort] = _
    private var curRefs = Refs(null, null)

    @volatile private var health: ContainerHealth =
        ContainerHealth(STOPPED, null, "Stopped")

    private var upNodes = Set[UUID]()
    private var downNodes = Set[UUID]()

    // Subscription to PoolHealthMonitorMap and container Port.
    private var refsSub: Subscription = _

    private val vtScheduler = Schedulers.from(vt.vtExecutor)
    private val containerScheduler = Schedulers.from(containerExecutor)

    private val statusSubject = PublishSubject.create[ContainerStatus]
    private val statusSubscription = Observable
        .interval(vt.config.containers.haproxy.statusUpdateInterval.toMillis,
                  TimeUnit.MILLISECONDS, vtScheduler)
        .doOnNext(makeAction1(publishStatus))
        .subscribe()

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
        ifaceName = port.interfaceName
        namespaceName = HaproxyHelper.namespaceName(lbId.toString)

        health = ContainerHealth(STARTING, namespaceName, "Starting")

        createPromise = Promise()
        val createFuture = createPromise.future

        unsubscribeRefs()
        subscribeRefs(port.portId)

        createFuture
    }

    // Shouldn't happen, so no need to do anything.
    override def updated(port: ContainerPort): Future[Option[String]] = {
        log.error("Unexpected call to HaProxyContainer.updated()")
        Future.successful(None)
    }

    private def unsubscribeRefs(): Unit =
        if (refsSub != null) {
            refsSub.unsubscribe()
            refsSub = null
        }

    private def subscribeRefs(portId: UUID): Unit = {
        assert(refsSub == null)
        poolHmMapObservable = VirtualTopology
            .observable(classOf[PoolHealthMonitorMap], PoolHealthMonitorMapKey)
            .observeOn(vtScheduler)
            .doOnCompleted(makeAction0(onPoolHmMapDeleted()))
            .doOnError(makeAction1(onPoolHmMapError))
        portObservable = VirtualTopology
            .observable(classOf[RouterPort], portId)
            .observeOn(vtScheduler)

        refsSub = Observable
            .merge(poolHmMapObservable, portObservable)
            .observeOn(containerScheduler)
            .map[Refs](makeFunc1(onSingleRefUpdated))
            .filter(makeFunc1(_.isReady))
            .doOnNext(makeAction1(onRefsUpdated))
            .subscribe()
    }

    private case class Refs(phm: PoolHealthMonitorMap, port: RouterPort) {
        def isReady: Boolean =
            port != null && phm != null &&
            phm.mappings.exists(_._2.loadBalancer.id == lbId)
    }

    private def onSingleRefUpdated(dev: Device): Refs = {
        log.debug("Received update: {}", dev)
        dev match {
            case phm: PoolHealthMonitorMap =>
                curRefs = Refs(phm, curRefs.port)
            case p: RouterPort =>
                curRefs = Refs(curRefs.phm, p)
        }
        curRefs
    }

    private def onRefsUpdated(refs: Refs): Unit = {
        val lbCfg = toLbV2Config(refs.phm)
        if (createPromise != null) {
            // Still need to deploy.
            val tryDeploy = Try {
                haProxyHelper.deploy(
                    lbCfg, ifaceName,
                    containerPortAddress(refs.port.portAddress4).toString,
                    routerPortAddress(refs.port.portAddress4).toString)
                Some(namespaceName)
            }

            createPromise.tryComplete(tryDeploy)
            createPromise = null // Done creating.

            health = tryDeploy match {
                case Success(_) =>
                    ContainerHealth(RUNNING, namespaceName, "Running")
                case Failure(ex) =>
                    log.error("Error deploying haproxy.", ex)
                    ContainerHealth(ERROR, namespaceName, ex.getMessage)
            }
        } else {
            // Already deployed. Just restart.
            try {
                haProxyHelper.restart(lbCfg)
            } catch {
                case NonFatal(ex) =>
                    log.error("Error restarting haproxy.", ex)
                    health = ContainerHealth(ERROR, namespaceName,
                                             ex.getMessage)
            }

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

            PoolV2Config(poolId, members.toSet, hmCfg)
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
        health = ContainerHealth(STOPPING, namespaceName, "Stopping")
        val result = cleanup(namespaceName)
        result.value.get match {
            case Success(_) =>
                health = ContainerHealth(STOPPED, namespaceName, "Stopped")
            case Failure(t) =>
                log.error("Error deleting container:", t)
                health = ContainerHealth(ERROR, namespaceName, t.getMessage)
        }
        result
    }

    /**
      * Cleans-up the container for the specified container name. The method
      * returns a future that completes when the container has been cleaned.
      */
    override def cleanup(name: String): Future[Unit] = {
        unsubscribeRefs()
        Future.fromTry(Try(haProxyHelper.undeploy(name, ifaceName)))
    }

    /**
      * Publishes container health status to the status observable, and
      * publishes pool member status to the NSDB store.
      */
    private def publishStatus(tick: java.lang.Long): Unit = {
        if (health.code == RUNNING) {
            val (newUpNodes, newDownNodes) = try {
                haProxyHelper.getStatus()
            } catch {
                case NonFatal(t) =>
                    log.error("HaProxyHelper.getStatus returned error.", t)
                    statusSubject.onNext(
                        ContainerHealth(ERROR, namespaceName, t.getMessage))
                    return
            }

            try updateMemberStatus(newUpNodes, newDownNodes) catch {
                case ex: NotFoundException =>
                    log.debug("NotFoundException when updating pool member " +
                              "status. Most likely due to concurrent delete " +
                              "of pool member.", ex)
                case NonFatal(t) =>
                    log.error("Unexpected exception updating pool member " +
                              "status.", t)
            }
        }
        statusSubject.onNext(health)
    }

    private def updateMemberStatus(newUpNodes: Set[UUID],
                                   newDownNodes: Set[UUID]): Unit = {
        val setUpIds = newUpNodes -- upNodes
        val setDownIds = newDownNodes -- downNodes

        try {
            val setUpResults = for (memberId <- setUpIds) yield
                vt.stateStore.addValue(classOf[PoolMember], memberId,
                                       StatusKey, LBStatus.ACTIVE.toString)

            val setDownResults = for (memberId <- setDownIds) yield
                vt.stateStore.addValue(classOf[PoolMember], memberId,
                                       StatusKey, LBStatus.INACTIVE.toString)

            // Must subscribe to result observables to make update take effect.
            Observable.merge((setUpResults ++ setDownResults).asJava)
                .toList.toBlocking.first

            upNodes = newUpNodes
            downNodes = newDownNodes
        } catch {
            case NonFatal(ex) =>
                log.warn("Error updating pool member status. Will try again " +
                          "next tick.", ex)
        }
    }

    // TODO: Publish ContainerOp updates in addition to ContainerHealth.
    override def status: Observable[ContainerStatus] = statusSubject.asObservable
}
