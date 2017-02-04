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
import java.util.concurrent._

import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.control.NonFatal
import scala.util.{Failure, Success}

import com.google.common.annotations.VisibleForTesting
import com.google.common.util.concurrent.AbstractService

import org.apache.curator.framework.state.ConnectionState
import org.apache.curator.framework.state.ConnectionState.{CONNECTED, RECONNECTED}
import org.apache.zookeeper.KeeperException
import org.reflections.Reflections

import rx.schedulers.Schedulers
import rx.{Observable, Subscriber, Subscription}

import org.midonet.cluster.data.storage.UnmodifiableStateException
import org.midonet.cluster.models.State.ContainerStatus.Code
import org.midonet.cluster.models.State.{ContainerServiceStatus, ContainerStatus => BackendStatus}
import org.midonet.cluster.models.Topology.{Host, ServiceContainer}
import org.midonet.cluster.services.MidonetBackend.{ContainerKey, StatusKey}
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.containers._
import org.midonet.midolman.containers.ContainerService.Operation.Operation
import org.midonet.midolman.containers.ContainerService._
import org.midonet.midolman.logging.MidolmanLogging
import org.midonet.midolman.topology.ContainerMapper.{Changed, Created, Deleted, Notification}
import org.midonet.midolman.topology.{ContainerMapper, DeviceMapper, VirtualTopology}
import org.midonet.util.concurrent._
import org.midonet.util.functors.{makeAction0, makeFunc1, makeFunc2, makeRunnable}
import org.midonet.util.reactivex._

object ContainerService {

    val NotificationBufferSize = 0x1000

    object Operation extends Enumeration {
        type Operation = Value
        val Create, Update, Delete = Value
    }

    case class ContainerInstance(id: UUID,
                                 context: ContainerContext,
                                 handler: Option[HandlerInstance])

    case class HandlerInstance(cp: ContainerPort,
                               handler: ContainerHandler,
                               subscription: Subscription)

    case class HostSelector(weight: Int, limit: Int, enforceLimit: Boolean)

}

/**
  * This service manages the containers at this host, identified by the `hostId`
  * argument.
  */
class ContainerService(vt: VirtualTopology, hostId: UUID,
                       serviceExecutor: ExecutorService,
                       containerExecutors: ContainerExecutors,
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

        @volatile var instance: ContainerInstance = null

        /**
          * Updates the status for the current container. The status can be
          * either a configuration or a health notification. The method logs a
          * warning if there is no handler for the container corresponding to
          * this subscriber.
          */
        override def onNext(status: ContainerStatus): Unit = {
            status match {
                case health: ContainerHealth =>
                    handlerOf(cp.portId) match {
                        case Some(handler) =>
                            setContainerStatus(instance, handler, health)
                        case None =>
                            log warn s"Unexpected health notification for " +
                                     s"container $cp"
                    }

                case op: ContainerOp =>
                    logOperation(cp, op)

                case _ => log warn "Unknown status notification for " +
                                   s"container $cp: $status"
            }
        }

        override def onCompleted(): Unit = {
            log info s"Container $cp stopped reporting container health status"
            clearContainerStatus(instance, cp)
        }

        override def onError(e: Throwable): Unit = {
            log.info(s"Container $cp notified an error when reporting " +
                     "the health status", e)
            setContainerStatus(instance, cp, e)
        }
    }

    private val scheduler = Schedulers.from(serviceExecutor)
    private val ec = ExecutionContext.fromExecutor(serviceExecutor)
    @volatile private var serviceThreadId = -1L

    private val containerMapper = new ContainerMapper(hostId, vt)
    private val containerObservable = Observable.create(containerMapper)

    private val provider =
        new ContainerHandlerProvider(reflections, vt, ioExecutor, log)

    private val logger = new ContainerLogger(vt.config.containers, log.wrapper)

    // The instances map is concurrent because reads may be performed from the
    // containers threads, while writes are always from the service thread.
    private val instances = new ConcurrentHashMap[UUID, ContainerInstance]

    private val hostReady = Promise[Unit]
    private val hostSubscriber = new Subscriber[Host] {
        override def onNext(host: Host): Unit = {
            try {
                currentHost = host
                setServiceStatus()
                hostReady.trySuccess(())
            } catch {
                case NonFatal(e) =>
                    log.warn("Failed to update the container service status", e)
                    hostReady tryFailure e
            }
        }

        override def onError(e: Throwable): Unit = {
            log.warn("Unexpected error on the host update stream", e)
            clearServiceStatus()
            hostReady tryFailure e
        }

        override def onCompleted(): Unit = {
            log.warn("Host update stream completed unexpectedly")
            clearServiceStatus()
            hostReady tryFailure new IllegalStateException(
                "Host update stream has completed")
        }
    }
    @volatile var currentHost: Host = null

    private val containerSubscriber = new Subscriber[Notification] {
        override def onNext(n: Notification): Unit = {
            try n match {
                case Created(cp) =>
                    createContainer(cp)
                case Changed(cp) =>
                    updateContainer(cp)
                case Deleted(cp) =>
                    deleteContainer(cp)
            } catch {
                case NonFatal(e) =>
                    log.error(s"Container notification $n failed", e)
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
        log info s"Starting Containers service for host $hostId " +
                 s"(${containerExecutors.count} container threads)"

        serviceExecutor execute makeRunnable {
            serviceThreadId = Thread.currentThread().getId
        }

        cleanupContainers()

        try {
            // Subscribe to the current host to update the service status with
            // the current container weight and quota.
            val hostObservable =
                vt.store.observable(classOf[Host], hostId)
                    .distinctUntilChanged[HostSelector](makeFunc1 { host =>
                        HostSelector(host.getContainerWeight,
                                     host.getContainerLimit,
                                     host.getEnforceContainerLimit)
                    })

            val connectionObservable =
                vt.backend.failFastConnectionState
                          .distinctUntilChanged()
                          .filter(makeFunc1(state =>
                              state == RECONNECTED || state == CONNECTED
                          ))

            Observable.combineLatest[Host, ConnectionState, Host](
                          hostObservable,
                          connectionObservable,
                          makeFunc2((h: Host, s: ConnectionState) => h))
                      .onBackpressureBuffer(NotificationBufferSize,
                                            makeAction0 { log error
                                                "Overflow on buffer used to " +
                                                "receive host and ZooKeeper " +
                                                "connection state notifications"
                                            })
                      .observeOn(scheduler)
                      .subscribe(hostSubscriber)

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
            hostReady.future.await(vt.config.zookeeper.sessionTimeout millis)

            notifyStarted()
        } catch {
            case NonFatal(e) =>
                log.warn("Failed to start the Containers service", e)
                // Unsubscribe from all notifications.
                hostSubscriber.unsubscribe()
                containerSubscriber.unsubscribe()
                notifyFailed(e)
        }
    }

    override protected def doStop(): Unit = {
        log info "Stopping Containers service: deleting all containers"
        clearServiceStatus()

        try {
            // Unsubscribe from all notifications.
            hostSubscriber.unsubscribe()
            containerSubscriber.unsubscribe()

            // Complete the mapper notification stream.
            containerMapper.complete().await(vt.config.containers.shutdownGraceTime)

            // Shutdown gracefully all containers
            implicit val ec = this.ec

            val futures = for (instance <- instances.values().asScala) yield {
                instance.handler match {
                    case Some(handler) => Future {
                        deleteContainer(handler.cp).recover {
                            case e =>
                                log.warn("Failed to delete container " +
                                         s"${handler.cp}", e)
                        }
                    }
                    case None =>
                        // Ignore instance without a handler.
                        Future.successful(())
                }
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
    protected[containers] def handlerList: Iterable[HandlerInstance] = {
        instances.values().asScala.flatMap(_.handler)
    }

    /**
      * Gets the current [[HandlerInstance]] from the instance of the specified
      * port identifier if it exists.
      */
    protected[containers] def handlerOf(portId: UUID): Option[HandlerInstance] = {
        instances get portId match {
            case instance: ContainerInstance => instance.handler
            case _ => None
        }
    }

    /**
      * Removes the container instance from the instances table. It is safe to
      * perform this operation because this method should be called on the
      * service thread, and only the service thread enqueues new tasks on the
      * context queue.
      */
    private def deleteInstance(instance: ContainerInstance): Unit = {
        assertServiceThread()
        if (instance.context.isDeletable) {
            log debug s"Deleting instance for container ${instance.id}"
            instances.remove(instance.id, instance)
            setServiceStatus()
        }
    }

    /**
      * Cleans the containers reported by the container log. The cleanup of
      * each container is done
      */
    private def cleanupContainers(): Unit = {
        try {
            // Check the container log if there are any previous containers, and
            // clear the container log.
            val containers = logger.currentContainers().asScala

            log debug s"Containers log includes ${containers.size} containers"

            for (container <- containers) yield {
                try {
                    log debug s"Cleanup container $container"
                    val context = ContainerContext(containerExecutors.nextExecutor())
                    val handler = provider.getInstance(container.`type`,
                                                       container.id,
                                                       serviceExecutor)
                    val instance = ContainerInstance(container.id, context, None)
                    instances.put(container.id, instance)
                    context.execute {
                        handler.cleanup(container.name)
                    }.andThen {
                        case _ => deleteInstance(instance)
                    }(ec)
                } catch {
                    case NonFatal(e) =>
                        log.warn(s"Failed to cleanup container $container")
                        Future.failed(e)
                }
            }

            logger.clear()

        } catch {
            case NonFatal(e) =>
                log.info("Failed to load the containers log: cleanup is not " +
                         "available", e)
        }
    }

    /**
      * Executes a function, wrapping and exceptions thrown by it with a failed
      * future.
      */
    private def tryOp(instance: ContainerInstance, cp: ContainerPort,
                      errorStatus: Boolean, op: Operation)
                     (f: => Future[Any]): Future[Any] = {
        try {
            f
        } catch {
            case NonFatal(e) =>
                handleContainerError(instance, e, cp, errorStatus, op)
                Future.failed(e)
        }
    }

    /**
      * Logs an error message and clears the container status for the given
      * [[Throwable]].
      */
    private def handleContainerError(instance: ContainerInstance, t: Throwable,
                                     cp: ContainerPort,
                                     errorStatus: Boolean, op: Operation): Unit = {
        if (instances.get(cp.portId) eq instance) {
            val message = s"Container $cp operation $op failed"
            log.error(message, t)
            if (errorStatus) setContainerStatus(instance, cp, t)
        }
    }

    /**
      * Returns the current container quota, which is the container limit
      * without the number of active container instances.
      */
    @inline
    private def currentQuota(host: Host, count: Int): Int = {
        if (host.getContainerLimit < 0) -1
        else Integer.max(host.getContainerLimit - count, 0)
    }

    /**
      * Sets the service status for the current host, which reports back to the
      * cluster the host container weight, and the container quota.
      */
    @throws[Throwable]
    private def setServiceStatus(): Unit = {
        val host = currentHost
        // Skip setting the status if the service has not initialized.
        if (host eq null) {
            return
        }

        val weight = host.getContainerWeight
        val limit = host.getContainerLimit
        val count = instances.size()
        val quota = currentQuota(host, count)

        log debug s"Container service is running with weight $weight " +
                  s"container count $count limit $limit quota $quota"
        val serviceStatus = ContainerServiceStatus.newBuilder()
            .setWeight(weight)
            .setQuota(quota)
            .setCount(count)
            .build()
        vt.stateStore.addValue(classOf[Host], hostId, ContainerKey,
                               serviceStatus.toString)
                     .await(vt.config.zookeeper.sessionTimeout millis)

    }

    /**
      * Clears the container service status for this host.
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

    /**
      * Creates a new container. The method starts by creating a new container
      * context for the new container. If a container instance for the same
      * identifier already exists, it deletes the previous container, and
      * reuses the same context. Then it creates a new container handler for
      * the current container type, it subscribes to container notifications,
      * adds the handler via a new instance to the instances map, and it calls
      * the container `create` method on the context thread. If the container
      * fails to start (either by returning a failed future or throwing an
      * exception), the method unsubscribes from notifications, deletes the
      * instance, and updates as needed the service status and container status.
      */
    @throws[Throwable]
    private def createContainer(cp: ContainerPort): Unit = {
        log info s"Create container for port binding $cp"

        assertServiceThread()

        // Check there is no container for this port: if there is, first delete
        // the container.
        val context = instances get cp.portId match {
            case i: ContainerInstance if i.handler.nonEmpty =>
                log warn s"Unexpected running container $cp: deleting " +
                         "container"
                deleteContainerForInstance(i)
                i.context
            case i: ContainerInstance =>
                i.context
            case _ =>
                ContainerContext(containerExecutors.nextExecutor())
        }

        // Verify the container quota.
        val host = currentHost
        if (currentQuota(host, instances.size()) == 0) {
            setContainerStatus(null, cp, Code.ERROR, "LIMIT_EXCEEDED")
            return
        }

        // Create a new container handler for this container type.
        val container = provider.getInstance(cp.serviceType, cp.portId,
                                             context.executor)

        // Subscribe to the handler's status observable.
        val subscriber = new StatusSubscriber(cp)
        val subscription = container.status.subscribe(subscriber)

        // Set the new handler for the container context.
        val handler = HandlerInstance(cp, container, subscription)

        // Create a new instance for this container.
        val instance = ContainerInstance(cp.portId, context, Some(handler))
        instances.put(cp.portId, instance)
        subscriber.instance = instance
        setServiceStatus()

        // Call the handler create method to initialize the container. We
        // add the initialization code inside a cargo in the conveyor belt such
        // that all container operations maintain the order.
        context.execute {
            tryOp(instance, cp, errorStatus = true, Operation.Create) {

                // Set the container status to starting.
                setContainerStatus(instance, cp, Code.STARTING,
                                   s"Container $cp starting")
                container.create(cp)
            }
        }.andThen {
            case Failure(t) =>
                // Handle failures on the service thread: update the container
                // status and delete the instance only if the instance matches
                // the instances table to prevent re-ordering of calls.
                subscription.unsubscribe()
                handleContainerError(instance, t, cp, errorStatus = true,
                                     Operation.Create)
                deleteInstance(instance)
            case Success(_) =>
        }(ec)
    }

    /**
      * Updates the container for the specified container port. The method
      * searches for the container instance, and if an instance with a handler
      * exists, it calls the `update` method on the container context thread.
      */
    @throws[Throwable]
    private def updateContainer(cp: ContainerPort): Unit = {
        log info s"Update container $cp"
        assertServiceThread()
        instances get cp.portId match {
            case instance: ContainerInstance if instance.handler.nonEmpty =>
                instance.context.execute {
                    tryOp(instance, cp, errorStatus = true, Operation.Update) {
                        instance.handler.get.handler.updated(cp)
                    }
                }.andThen {
                    case Failure(t) =>
                        // Handle failures on the service thread and validate
                        // against the instances table.
                        handleContainerError(instance, t, cp, errorStatus = true,
                                             Operation.Update)
                    case _ =>
                }(ec)
            case _ => log warn s"There is no container $cp"
        }
    }

    /**
      * Deletes the container for the specified container port. The method
      * searches for the container instance, and if an instance with a handler
      * exists, it calls the [[deleteContainerForInstance()]] method which
      * executes the container `delete` method on the container context thread,
      * and then it calls the [[deleteInstance()]] method on the service
      * thread, which removes the instance from the instances map.
      *
      * This method must be called on the service thread.
      */
    @throws[Throwable]
    private def deleteContainer(cp: ContainerPort): Future[Any] = {
        log info s"Delete container $cp"
        assertServiceThread()
        instances get cp.portId match {
            case instance: ContainerInstance if instance.handler.nonEmpty =>
                deleteContainerForInstance(instance).andThen {
                    // On the service thread, try to delete the instance after
                    // the last container operation has completed.
                    case _ => deleteInstance(instance)
                }(ec)
            case instance: ContainerInstance =>
                log warn s"There is no container $cp"
                deleteInstance(instance)
                Future.successful(null)
            case _ =>
                log warn s"There is no container $cp"
                Future.successful(null)
        }
    }

    /**
      * Deletes the container for the specified instance.
      */
    private def deleteContainerForInstance(instance: ContainerInstance)
    : Future[Any] = {
        val handler = instance.handler.getOrElse {
            return Future.successful(())
        }
        instance.context.execute {
            tryOp(instance, handler.cp, errorStatus = false, Operation.Delete) {
                // Set the container status to stopping.
                setContainerStatus(instance, handler.cp, Code.STOPPING,
                                   s"Container ${handler.cp} stopping")
                handler.handler.delete().andThen {
                    case _ => handler.subscription.unsubscribe()
                }(instance.context.ec).andThen {
                    // Handle the errors and status updates on the service
                    // thread and validate against the instances table.
                    case Failure(t) =>
                        handleContainerError(instance, t, handler.cp,
                                             errorStatus = false,
                                             Operation.Delete)
                    case Success(_) =>
                        clearContainerStatus(instance, handler.cp)
                }(ec)
            }
        }
    }

    @inline
    private def setContainerStatus(instance: ContainerInstance,
                                   handler: HandlerInstance,
                                   health: ContainerHealth): Unit = {
        val status = BackendStatus.newBuilder()
                                  .setStatusCode(health.code)
                                  .setStatusMessage(health.message)
                                  .setHostId(hostId.asProto)
                                  .setNamespaceName(health.namespace)
                                  .setInterfaceName(handler.cp.interfaceName)
                                  .build()
        setContainerStatus(instance, handler.cp, status)
    }

    @inline
    private def setContainerStatus(instance: ContainerInstance,
                                   cp: ContainerPort, e: Throwable): Unit = {
        val message = if (e.getMessage ne null) e.getMessage else ""
        setContainerStatus(instance, cp, Code.ERROR, message)
    }

    @inline
    private def setContainerStatus(instance: ContainerInstance,
                                   cp: ContainerPort, code: Code,
                                   message: String): Unit = {
        val status = BackendStatus.newBuilder()
                                  .setStatusCode(code)
                                  .setStatusMessage(message)
                                  .setHostId(hostId.asProto)
                                  .setInterfaceName(cp.interfaceName)
                                  .build()
        setContainerStatus(instance, cp, status)
    }

    @inline
    private def setContainerStatus(instance: ContainerInstance,
                                   cp: ContainerPort, status: BackendStatus)
    : Unit = {
        try {
            // Verify the instance matches the instances table or null (only
            // used when the container limit is exceeded).
            if ((instance eq null) || (instances.get(cp.portId) eq instance)) {
                vt.stateStore.addValue(classOf[ServiceContainer], cp.containerId,
                                       StatusKey, status.toString).await()
            }
        } catch {
            case t: UnmodifiableStateException
                if t.result == KeeperException.Code.NONODE.intValue() =>
                log info s"Failed to write status ${status.getStatusCode} " +
                         s"for container $cp: container deleted"
            case NonFatal(t) =>
                log.error(s"Failed to write status ${status.getStatusCode} " +
                          s"for container $cp", t)
        }
    }

    @inline
    private def clearContainerStatus(instance: ContainerInstance,
                                     cp: ContainerPort): Unit = {
        try {
            if (instances.get(cp.portId) eq instance) {
                vt.stateStore.removeValue(classOf[ServiceContainer],
                                          cp.containerId, StatusKey, null)
                             .await()
            }
        } catch {
            case t: UnmodifiableStateException
                if t.result == KeeperException.Code.NONODE.intValue() =>
                log info s"Failed to clear status for container $cp: " +
                         "container deleted"
            case NonFatal(t) =>
                log.warn(s"Failed to clear status for container $cp", t)
        }
    }

    @inline
    private def assertServiceThread(): Unit = {
        if (Thread.currentThread().getId != serviceThreadId) {
            val e = new IllegalStateException(
                "Method must be called on the service executor thread " +
                s"$serviceThreadId instead called on thread " +
                s"${Thread.currentThread().getId}:${Thread.currentThread().getName}")
            log.error(e.getMessage, e)
        }
    }

    private def logOperation(cp: ContainerPort, op: ContainerOp)
    : Unit = {
        try {
            logger.log(cp.serviceType, cp.portId, op)
        } catch {
            case NonFatal(t) =>
                log.warn(s"Failed to log operation for container $cp: $op", t)
        }
    }

}
