/*
 * Copyright 2015 Midokura SARL
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

package org.midonet.midolman.containers

import java.util.UUID
import java.util.concurrent.{ConcurrentHashMap, ExecutorService}

import scala.async.Async.async
import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

import com.google.common.annotations.VisibleForTesting
import com.google.common.util.concurrent.AbstractService

import rx.schedulers.Schedulers
import rx.{Observable, Subscriber, Subscription}

import org.midonet.cluster.models.State.ContainerStatus
import org.midonet.cluster.models.State.ContainerStatus.Code
import org.midonet.cluster.models.Topology.{Host, ServiceContainer, ServiceContainerGroup}
import org.midonet.cluster.services.MidonetBackend.{ContainerKey, StatusKey}
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.midolman.logging.MidolmanLogging
import org.midonet.midolman.topology.ContainerMapper.{Changed, Created, Deleted, Notification}
import org.midonet.midolman.topology.{ContainerMapper, DeviceMapper, VirtualTopology}
import org.midonet.util.concurrent._
import org.midonet.util.functors.makeAction0
import org.midonet.util.reactivex._

object ContainerService {

    private val NotificationBufferSize = 0x1000
    private val StorageTimeout = Duration.Inf

    case class Handler(cp: ContainerPort, handler: ContainerHandler,
                       namespace: String, subscription: Subscription)

}

/**
  * This service manages the containers at this host, identified by the `hostId`
  * argument.
  */
class ContainerService(vt: VirtualTopology, hostId: UUID,
                       executor: ExecutorService)
    extends AbstractService with MidolmanLogging {

    import ContainerService._

    override def logSource = "org.midonet.containers"

    /**
      * Provides a subscriber for the health notifications emitted by a
      * container handler.
      */
    private class HealthSubscriber(cp: ContainerPort)
        extends Subscriber[ContainerHealth] {

        /**
          * Updates the status for the current container with the status code
          * and message notified by the container handler. The method logs a
          * warning if there is no handler for the container corresponding to
          * this subscriber.
          */
        override def onNext(health: ContainerHealth): Unit = {
            handlers get cp.portId match {
                case handler: Handler =>
                    setStatus(handler, health.code, health.message)
                case _ =>
                    log warn s"Unexpected health notification for container $cp"
            }
        }

        override def onCompleted(): Unit = {
            log info s"Container $cp health stream completed"
            clearStatus(cp)
        }

        override def onError(e: Throwable): Unit = {
            log info s"Container $cp health stream completed with error"
            setStatus(cp, e)
        }
    }

    private val scheduler = Schedulers.from(executor)
    private implicit val ec = ExecutionContext.fromExecutor(executor)

    private val mapper = new ContainerMapper(hostId, vt)
    private val observable = Observable.create(mapper)

    private val provider = new ContainerHandlerProvider(
        "org.midonet.midolman.containers", vt, log)

    // The handlers map is concurrent because reads may be performed from
    // the handler notification thread.
    private val handlers = new ConcurrentHashMap[UUID, Handler]

    private val containerSubscriber = new Subscriber[Notification] {
        override def onNext(n: Notification): Unit = n match {
            case Created(cp) =>
                tryOp(cp, errorStatus = true, s"Failed to create container $cp") {
                    createContainer(cp)
                }
            case Changed(cp) =>
                tryOp(cp, errorStatus = true, s"Failed to update container $cp") {
                    updateContainer(cp)
                }
            case Deleted(cp) =>
                tryOp(cp, errorStatus = false, s"Failed to delete container $cp") {
                    deleteContainer(cp)
                }
        }

        override def onError(e: Throwable): Unit = e match {
            case DeviceMapper.MapperClosedException =>
                // We ignore this exception because it is always emitted when
                // the mapper is closed using the `complete` method
            case _ =>
                // We should never get here since the container mapper should
                // filter all errors: it represents a serious bug.
                log.error("Unexpected error on the container notification " +
                          "stream", e)
        }

        override def onCompleted(): Unit = {
            log debug "Container notification stream completed"
        }
    }

    override protected def doStart(): Unit = {
        log info s"Starting Containers service for host $hostId"

        try {
            // Subscribe to the observable stream of the current host. The
            // subscription is done on the containers executor with a
            // back-pressure buffer to ensure that all notifications are
            // cached if the container executor is busy for a longer interval.
            observable
                .onBackpressureBuffer(NotificationBufferSize, makeAction0 {
                    log error "Notification buffer overflow"
                })
                .observeOn(scheduler)
                .subscribe(containerSubscriber)

            // Report the status of the containers service at this host.
            vt.stateStore.addValue(classOf[Host], hostId, ContainerKey,
                                   hostId.toString)
                         .await(StorageTimeout)

            notifyStarted()
        } catch {
            case NonFatal(e) =>
                log.warn("Failed to start the Containers service", e)
                notifyFailed(e)
        }
    }

    override protected def doStop(): Unit = {
        log info "Stopping Containers service: deleting all containers"
        try {
            // Report the status of the containers service at this host.
            vt.stateStore.removeValue(classOf[Host], hostId, ContainerKey,
                                      value = null)
                         .await()

            // Unsubscribe from all notifications.
            containerSubscriber.unsubscribe()

            // Complete the mapper notification stream.
            mapper.complete().await(vt.config.containers.shutdownGraceTime seconds)

            // Shutdown gracefully all containers
            val futures = for (handler <- handlers.values().asScala) yield async {
                tryOp(handler.cp, errorStatus = false,
                      s"Failed to delete container ${handler.cp}") {
                    deleteContainer(handler.cp)
                }
            }
            Future.sequence(futures)
                  .await(vt.config.containers.shutdownGraceTime seconds)

            notifyStopped()
        } catch {
            case NonFatal(e) =>
                log.warn("Failed to stop the Containers service", e)
                notifyFailed(e)
        }
    }

    @VisibleForTesting
    protected[containers] def handlerList: Iterable[Handler] = {
        handlers.values().asScala
    }

    @VisibleForTesting
    protected[containers] def handlerOf(portId: UUID): Handler = {
        handlers.get(portId)
    }

    private def tryOp(cp: ContainerPort, errorStatus: Boolean, message: => String)
                     (f: => Unit): Unit = {
        try {
            f
        } catch {
            case NonFatal(e) =>
                log.error(message, e)
                if (errorStatus) {
                    setStatus(cp, e)
                }
        }
    }

    @throws[Throwable]
    private def createContainer(cp: ContainerPort): Unit = {
        log info s"Create container for port binding $cp"

        // Check there is no container for this port: if there is, first delete
        // the container.
        handlers remove cp.portId match {
            case Handler(_,h,_,s) =>
                log warn s"Unexpected running container $cp: deleting container"
                s.unsubscribe()
                tryOp(cp, errorStatus = false, s"Failed to delete container $cp") {
                    h.delete().await(vt.config.containers.timeout seconds)
                }
            case _ => // Normal case
        }

        // Create a new container handler for this container type.
        val handler = provider.getInstance(cp.serviceType)

        // Subscribe to the handler's health observable.
        val subscription = handler.health.subscribe(new HealthSubscriber(cp))

        try {
            // Set the container status to starting.
            setStatus(cp, Code.STARTING, s"Container $cp starting")

            // Call the handler create method to initialize the container. We
            // wait on the current thread for container to initialize such that
            // all container operations maintain the order.
            val namespace = handler.create(cp)
                                   .await(vt.config.containers.timeout seconds)
            handlers.put(cp.portId, Handler(cp, handler, namespace, subscription))
        } catch {
            case NonFatal(e) =>
                // If the container failed to initialize, remove its handler
                // from the handlers list and unsubscribe from the health
                // observable.
                // NOTE: The container handler implementation must provide
                // the appropriate cleanup on failure.
                handlers remove cp.portId
                subscription.unsubscribe()
                clearStatus(cp)
                throw e
        }
    }

    @throws[Throwable]
    private def updateContainer(cp: ContainerPort): Unit = {
        log info s"Update container $cp"

        handlers get cp.portId match {
            case Handler(_,handler,_,_) =>
                handler.updated(cp).await(vt.config.containers.timeout seconds)
            case _ => log warn s"There is no container $cp"
        }
    }

    @throws[Throwable]
    private def deleteContainer(cp: ContainerPort): Unit = {
        log info s"Delete container $cp"

        handlers remove cp.portId match {
            case Handler(_,handler,_,subscription) =>
                subscription.unsubscribe()
                clearStatus(cp)
                handler.delete().await(vt.config.containers.timeout seconds)
            case _ => log warn s"There is no container $cp"
        }
    }

    @inline
    private def setStatus(handler: Handler, code: Code, message: String): Unit = {
        val status = ContainerStatus.newBuilder()
                                    .setStatusCode(code)
                                    .setStatusMessage(message)
                                    .setHostId(hostId.asProto)
                                    .setNamespaceName(handler.namespace)
                                    .setInterfaceName(handler.cp.interfaceName)
                                    .build()
        setStatus(handler.cp, status)
    }

    @inline
    private def setStatus(cp: ContainerPort, e: Throwable): Unit = {
        val message = if (e.getMessage ne null) e.getMessage else ""
        setStatus(cp, Code.ERROR, message)
    }

    @inline
    private def setStatus(cp: ContainerPort, code: Code, message: String): Unit = {
        val status = ContainerStatus.newBuilder()
            .setStatusCode(code)
            .setStatusMessage(message)
            .setHostId(hostId.asProto)
            .setInterfaceName(cp.interfaceName)
            .build()
        setStatus(cp, status)
    }

    @inline
    private def setStatus(cp: ContainerPort, status: ContainerStatus): Unit = {
        try {
            vt.stateStore.addValue(classOf[ServiceContainer], cp.containerId,
                                   StatusKey, status.toString).await()
        } catch {
            case NonFatal(t) =>
                log.error(s"Failed to write status ${status.getStatusCode} " +
                          s"for container $cp", t)
        }
    }

    @inline
    private def clearStatus(cp: ContainerPort): Unit = {
        try {
            vt.stateStore.removeValue(classOf[ServiceContainer], cp.containerId,
                                      StatusKey, null).await()
        } catch {
            case NonFatal(t) =>
                log.warn(s"Failed to clear status for container $cp", t)
        }
    }

}
