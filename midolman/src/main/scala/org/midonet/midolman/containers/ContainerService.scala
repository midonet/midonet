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

package org.midonet.midolman.containers

import java.util.UUID
import java.util.concurrent.{ScheduledExecutorService, ConcurrentHashMap, ExecutorService}

import scala.async.Async.async
import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.concurrent.{Promise, ExecutionContext, Future}
import scala.util.control.NonFatal
import scala.util.{Failure, Success}

import com.google.common.annotations.VisibleForTesting
import com.google.common.util.concurrent.AbstractService
import org.apache.curator.framework.state.ConnectionState
import org.apache.curator.framework.state.ConnectionState.{CONNECTED, RECONNECTED}
import org.reflections.Reflections
import rx.schedulers.Schedulers
import rx.{Observable, Subscriber, Subscription}

import org.midonet.cluster.models.State.ContainerStatus.Code
import org.midonet.cluster.models.State.{ContainerServiceStatus, ContainerStatus => BackendStatus}
import org.midonet.cluster.models.Topology.{Host, ServiceContainer}
import org.midonet.cluster.services.MidonetBackend.{ContainerKey, StatusKey}
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.midolman.containers.ContainerService._
import org.midonet.midolman.logging.MidolmanLogging
import org.midonet.midolman.topology.ContainerMapper.{Changed, Created, Deleted, Notification}
import org.midonet.midolman.topology.{ContainerMapper, DeviceMapper, VirtualTopology}
import org.midonet.util.concurrent._
import org.midonet.util.functors.{makeAction0, makeFunc1, makeFunc2}
import org.midonet.util.reactivex._

object ContainerService {

    private val NotificationBufferSize = 0x1000

    case class Handler(cp: ContainerPort, handler: ContainerHandler,
                       subscription: Subscription)

}

/**
  * This service manages the containers at this host, identified by the `hostId`
  * argument.
  */
class ContainerService(vt: VirtualTopology, hostId: UUID,
                       serviceExecutor: ExecutorService,
                       ioExecutor: ScheduledExecutorService,
                       reflections: Reflections)
    extends AbstractService with MidolmanLogging {

    override def logSource = "org.midonet.containers"

    /**
      * Provides a subscriber for the status notifications emitted by a
      * container handler.
      */
    private class StatusSubscriber(cp: ContainerPort)
        extends Subscriber[ContainerStatus] {

        /**
          * Updates the status for the current container. The status can be
          * either a configuration or a health notification. The method logs a
          * warning if there is no handler for the container corresponding to
          * this subscriber.
          */
        override def onNext(status: ContainerStatus): Unit = {
            status match {
                case health: ContainerHealth =>
                    handlers get cp.portId match {
                        case handler: Handler => setStatus(handler, health)
                        case _ =>
                            log warn s"Unexpected health notification for " +
                                     s"container $cp"
                    }
                case config: ContainerConfiguration =>
                    setConfiguration(cp, config)
                case _ => log warn "Unknown status notification for " +
                                   s"container $cp: $status"
            }
        }

        override def onCompleted(): Unit = {
            log info s"Container $cp stopped reporting container health status"
            clearStatus(cp)
        }

        override def onError(e: Throwable): Unit = {
            log.info(s"Container $cp notified an error when reporting " +
                     "the health status", e)
            setStatus(cp, e)
        }
    }

    private val scheduler = Schedulers.from(serviceExecutor)
    private implicit val ec = ExecutionContext.fromExecutor(serviceExecutor)

    private val containerMapper = new ContainerMapper(hostId, vt)
    private val containerObservable = Observable.create(containerMapper)

    private val provider =
        new ContainerHandlerProvider(reflections, vt, serviceExecutor,
                                     ioExecutor, log)

    private val logger = new ContainerLogger(vt.config.containers, log)

    // The handlers map is concurrent because reads may be performed from
    // the handler notification thread.
    private val handlers = new ConcurrentHashMap[UUID, Handler]

    private val weightReady = Promise[Unit]
    private val weightSubscriber = new Subscriber[Int] {
        override def onNext(weight: Int): Unit = {
            try {
                setServiceStatus(weight)
                weightReady.trySuccess(())
            } catch {
                case NonFatal(e) =>
                    log.warn("Failed to update the container service status", e)
                    weightReady tryFailure e
            }
        }

        override def onError(e: Throwable): Unit = {
            log.warn("Unexpected error on the container weight update stream", e)
            weightReady tryFailure e
        }

        override def onCompleted(): Unit = {
            log.warn("Container weight update stream completed unexpectedly")
            weightReady tryFailure new IllegalStateException(
                "Container weight update stream has completed")
        }
    }

    private val belt = new ConveyorBelt(Throwable => {})

    private val containerSubscriber = new Subscriber[Notification] {
        override def onNext(n: Notification): Unit = n match {
            case Created(cp) =>
                createContainer(cp)
            case Changed(cp) =>
                updateContainer(cp)
            case Deleted(cp) =>
                deleteContainer(cp)
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
            // Subscribe to the current host to update the host status with
            // the current weight.
            val weightObservable =
                vt.store.observable(classOf[Host], hostId)
                    .map[Int](makeFunc1(_.getContainerWeight))
                    .distinctUntilChanged()

            val connectionObservable =
                vt.backend.failFastConnectionState
                          .distinctUntilChanged()
                          .filter(makeFunc1(state =>
                              state == RECONNECTED || state == CONNECTED
                          ))

            Observable.combineLatest[Int, ConnectionState, Int](
                          weightObservable,
                          connectionObservable,
                          makeFunc2((w: Int, s: ConnectionState) => w))
                      .onBackpressureBuffer(NotificationBufferSize,
                                            makeAction0 { log error
                                                "Overflow on buffer used to " +
                                                "receive host and ZooKeeper " +
                                                "connection state notifications"
                                            })
                      .observeOn(scheduler)
                      .subscribe(weightSubscriber)

            // Subscribe to the observable stream of the current host. The
            // subscription is done on the containers executor with a
            // back-pressure buffer to ensure that all notifications are
            // cached if the container executor is busy for a longer interval.
            containerObservable
                .onBackpressureBuffer(NotificationBufferSize, makeAction0 {
                    log error "Notification buffer overflow"
                })
                .observeOn(scheduler)
                .subscribe(containerSubscriber)

            // Wait for the status of the containers service at this host.
            weightReady.future.await(vt.config.zookeeper.sessionTimeout millis)

            notifyStarted()
        } catch {
            case NonFatal(e) =>
                log.warn("Failed to start the Containers service", e)
                // Unsubscribe from all notifications.
                weightSubscriber.unsubscribe()
                containerSubscriber.unsubscribe()
                notifyFailed(e)
        }
    }

    override protected def doStop(): Unit = {
        log info "Stopping Containers service: deleting all containers"
        clearServiceStatus()

        try {
            // Unsubscribe from all notifications.
            weightSubscriber.unsubscribe()
            containerSubscriber.unsubscribe()

            // Complete the mapper notification stream.
            containerMapper.complete().await(vt.config.containers.shutdownGraceTime)

            // Shutdown gracefully all containers
            val futures = for (handler <- handlers.values().asScala) yield async {
                deleteContainer(handler.cp)
            }
            Future.sequence(futures)
                  .await(vt.config.containers.shutdownGraceTime)

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

    private def tryOp(f: => Future[_], cp: ContainerPort, errorStatus: Boolean,
                      message: String): Future[_] = {
        try {
            f
        } catch {
            case NonFatal(e) =>
                handleContainerError(e, cp, errorStatus, message)
                Future.failed(e)
        }
    }

    private def handleContainerError(t: Throwable, cp: ContainerPort,
                                     errorStatus: Boolean, message: String): Unit = {
        log.error(message, t)
        if (errorStatus) setStatus(cp, t)
    }

    @throws[Throwable]
    private def setServiceStatus(weight: Int): Unit = {
        log debug s"Container service is running with weight $weight"
        val serviceStatus = ContainerServiceStatus.newBuilder()
            .setWeight(weight)
            .build()
        vt.stateStore.addValue(classOf[Host], hostId, ContainerKey,
                               serviceStatus.toString)
                     .await(vt.config.zookeeper.sessionTimeout millis)
    }

    /** Clears the container service status for this host.
      */
    private def clearServiceStatus(): Unit = {
        try {
            // Report the status of the containers service at this host.
            vt.stateStore.removeValue(classOf[Host], hostId, ContainerKey,
                                      value = null)
                         .await(vt.config.zookeeper.sessionTimeout millis)
        } catch {
            case NonFatal(e) =>
                log.warn("Failed to update the status of the container service", e)
                notifyFailed(e)
        }

    }

    @throws[Throwable]
    private def createContainer(cp: ContainerPort): Unit = {
        log info s"Create container for port binding $cp"

        // Check there is no container for this port: if there is, first delete
        // the container.
        handlers remove cp.portId match {
            case Handler(_,handler,s) =>
                log warn s"Unexpected running container $cp: deleting container"
                belt.handle(() => tryOp({
                    handler.delete() andThen {
                        case _ => s.unsubscribe()
                    } andThen {
                        case Failure(t) =>
                            handleContainerError(t, cp, errorStatus=false,
                                                 s"Failed to delete container $cp")
                        case Success(_) =>
                            clearStatus(cp)
                    }
                }, cp, errorStatus=false, s"Failed to delete container $cp"))
            case _ => // Normal case
        }

        // Call the handler create method to initialize the container. We
        // add the initialization code inside a cargo in the conveyor belt such
        // that all container operations maintain the order.
        belt.handle(() => tryOp({
            // Create a new container handler for this container type.
            val handler = provider.getInstance(cp.serviceType)

            // Subscribe to the handler's status observable.
            val subscription = handler.status
                .subscribe(new StatusSubscriber(cp))

            handlers.put(cp.portId, Handler(cp, handler, subscription))

            // Set the container status to starting.
            setStatus(cp, Code.STARTING, s"Container $cp starting")
            try {
                handler.create(cp).andThen {
                    case Failure(t) =>
                        handlers remove cp.portId
                        handleContainerError(t, cp, errorStatus=true,
                                             s"Failed to create container $cp")
                    case _ =>
                }
            }
            catch {
                case NonFatal(e) =>
                    // If the container failed to initialize, remove its handler
                    // from the handlers list and unsubscribe from the health
                    // observable.
                    // NOTE: The container handler implementation must provide
                    // the appropriate cleanup on failure.
                    handlers remove cp.portId
                    subscription.unsubscribe()
                    throw e
            }

        }, cp, errorStatus=true, s"Failed to create container $cp"))
    }

    @throws[Throwable]
    private def updateContainer(cp: ContainerPort): Unit = {
        log info s"Update container $cp"

        handlers get cp.portId match {
            case Handler(_,handler,_) =>
                belt.handle(() => tryOp({
                    handler.updated(cp).andThen {
                        case Failure(t) => handleContainerError(
                            t, cp, errorStatus=true, s"Failed to update container $cp")
                        case _ =>
                    }
                }, cp, errorStatus=true, s"Failed to update container $cp"))
            case _ => log warn s"There is no container $cp"
        }
    }

    @throws[Throwable]
    private def deleteContainer(cp: ContainerPort): Unit = {
        log info s"Delete container $cp"

        handlers remove cp.portId match {
            case Handler(_,handler,subscription) =>
                // Delete the container handler.
                belt.handle(() => tryOp({
                    // Set the container status to stopping.
                    setStatus(cp, Code.STOPPING, s"Container $cp stopping")
                    handler.delete() andThen {
                        case _ => subscription.unsubscribe()
                    }  andThen {
                        case Failure(t) =>
                            handleContainerError(t, cp, errorStatus=false,
                                                 s"Failed to delete container $cp")
                        case Success(_) =>
                            clearStatus(cp)
                    }
                }, cp, errorStatus=false, s"Failed to delete container $cp"))
            case _ => log warn s"There is no container $cp"
        }
    }

    @inline
    private def setStatus(handler: Handler, health: ContainerHealth): Unit = {
        val status = BackendStatus.newBuilder()
                                  .setStatusCode(health.code)
                                  .setStatusMessage(health.message)
                                  .setHostId(hostId.asProto)
                                  .setNamespaceName(health.namespace)
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
        val status = BackendStatus.newBuilder()
                                  .setStatusCode(code)
                                  .setStatusMessage(message)
                                  .setHostId(hostId.asProto)
                                  .setInterfaceName(cp.interfaceName)
                                  .build()
        setStatus(cp, status)
    }

    @inline
    private def setStatus(cp: ContainerPort, status: BackendStatus): Unit = {
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

    private def setConfiguration(cp: ContainerPort, config: ContainerConfiguration)
    : Unit = {
        try {
            logger.log(cp.serviceType, config)
        } catch {
            case NonFatal(t) =>
                log.warn("Failed to log container operation", t)
        }
    }

}
