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

package org.midonet.midolman.vpp

import java.util
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.{TimeUnit, TimeoutException}
import java.util.{Collections, UUID, List => JList}

import javax.annotation.concurrent.NotThreadSafe

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.util.control.NonFatal

import com.google.common.collect.ImmutableList

import rx.Observable.OnSubscribe
import rx.subjects.PublishSubject
import rx.{Observable, Observer, Subscriber, Subscription}

import org.midonet.cluster.util.UUIDUtil
import org.midonet.midolman.services.MidolmanActorsService._
import org.midonet.midolman.simulation.{Port, PortGroup, RouterPort}
import org.midonet.midolman.topology.VirtualToPhysicalMapper.LocalPortActive
import org.midonet.midolman.topology.{ObjectReferenceTracker, VirtualToPhysicalMapper, VirtualTopology}
import org.midonet.midolman.vpp.VppFip64.Notification
import org.midonet.midolman.vpp.VppUplink.UplinkState
import org.midonet.packets.IPv6Subnet
import org.midonet.util.functors.{makeAction0, makeAction1, makeFunc1, makeRunnable}
import org.midonet.util.logging.Logger

object VppUplink {

    /**
      * A message to configure an IPv6 uplink port. The message contains the
      * IPv6 address of the uplink port and the set of ports that belong to the
      * same port group as the current port.
      */
    case class AddUplink(portId: UUID,
                         routerId: UUID,
                         portAddress: IPv6Subnet,
                         uplinkPortIds: JList[UUID]) extends Notification

    /**
      * A message to delete a configured IPv6 uplink port.
      */
    case class DeleteUplink(portId: UUID) extends Notification

    /**
      * Maintains the state for an uplink port, which includes monitoring the
      * port's stateful port groups in order to maintain the list of other
      * ports that share the port's NAT64 state and NAT64 pools.
      */
    private class UplinkState(val portId: UUID, vt: VirtualTopology,
                              log: Logger) {

        private object DeleteThisUplink extends DeleteUplink(portId)

        private val NoPortGroupPortIds = Collections.singletonList(portId)

        private var currentPort: RouterPort = _
        private var last: AddUplink = _
        private val mark = PublishSubject.create[RouterPort]()

        private val portGroupTracker = new ObjectReferenceTracker[PortGroup](
            vt, classOf[PortGroup], log)

        private val portObservable = VirtualTopology
            .observable(classOf[Port], portId)
            .takeUntil(mark)
            .takeWhile(makeFunc1(isRouterPort))
            .doOnCompleted(makeAction0(portDeleted()))
            .doOnNext(makeAction1(portUpdated))

        private val portGroupObservable = portGroupTracker.refsObservable
            .doOnNext(makeAction1(portGroupUpdated))

        private val portCleanup: Observable[Notification] = {
            Observable.create(new OnSubscribe[Notification] {
                override def call(child: Subscriber[_ >: Notification]): Unit = {
                    cleanup(child)
                }
            })
        }

        /**
          * An [[Observable]] that will emit the uplink notifications for the
          * current uplink port.
          */
        val observable: Observable[Notification] = Observable
            .merge(portGroupObservable, portObservable)
            .filter(makeFunc1(_ => isReady))
            .flatMap[Notification](makeFunc1(buildNotification))
            .onErrorResumeNext(makeFunc1(handleErrors))
            .concatWith(portCleanup)

        /**
          * Completes the observable exposed by this [[UplinkState]]. Before
          * completing, the observable will emit any necessary cleanup
          * notifications.
          */
        def complete(): Unit = {
            mark.onCompleted()
        }

        /**
          * Indicates whether the state has received the uplink port and its
          * corresponding port groups and is ready to emit FIP64 [[Notification]]s.
          */
        @inline def isReady: Boolean = {
            (currentPort ne null) && portGroupTracker.areRefsReady
        }

        /**
          * Returns true if the port is a router port with an IPv6 address.
          */
        @inline private def isRouterPort(port: Port): Boolean = {
            port.isInstanceOf[RouterPort]
        }

        /**
          * Handles the port deletion, by completing the output observable.
          */
        private def portDeleted(): Unit = {
            log debug s"Port $portId deleted or not a router port"
            portGroupTracker.completeRefs()
        }

        /**
          * Handles updates for the uplink port. The method updates the tracked
          * port groups, and when both the port and the port groups are ready,
          * it will emit a [[AddUplink]] notification.
          */
        private def portUpdated(port: Port): Unit = {
            log debug s"Port updated $port"

            // Track the current port groups.
            portGroupTracker.requestRefs(port.portGroups.asScala: _*)
            currentPort = port.asInstanceOf[RouterPort]
        }

        /**
          * Handles updates to the port groups for the current uplink port.
          */
        private def portGroupUpdated(portGroup: PortGroup): Unit = {
            log debug s"Port group updated: $portGroup"
        }

        /**
          * Builds a sequence of uplink notifications based on the changes to
          * the uplink port and its corresponding port group.
          */
        private def buildNotification(any: AnyRef): Observable[Notification] = {
            if (last eq null) {
                if (currentPort.portAddress6 ne null) {
                    val uplinkPortIds = uplinkPorts
                    log debug s"Uplink port $portId with IPv6 address " +
                              s"${currentPort.portAddress6} and stateful port " +
                              s"group $uplinkPortIds"
                    last = AddUplink(portId, currentPort.routerId,
                                     currentPort.portAddress6, uplinkPortIds)
                    Observable.just(last)
                } else {
                    log debug s"Uplink port $portId has no IPv6 address"
                    Observable.empty()
                }
            } else {
                if (currentPort.portAddress6 ne null) {
                    val uplinkPortIds = uplinkPorts
                    if (last.portAddress != currentPort.portAddress6) {
                        log debug s"Uplink port $portId IPv6 address has " +
                                  s"changed from ${last.portAddress} to " +
                                  s"${currentPort.portAddress6}"
                        last = AddUplink(portId, currentPort.routerId,
                                         currentPort.portAddress6,
                                         uplinkPortIds)
                        Observable.just(DeleteThisUplink, last)
                    } else if (last.uplinkPortIds != uplinkPortIds) {
                        log debug s"Uplink port $portId port group membership " +
                                  s"has changed from ${last.uplinkPortIds} to " +
                                  s"$uplinkPortIds"
                        last = AddUplink(portId, currentPort.routerId,
                                         currentPort.portAddress6,
                                         uplinkPortIds)
                        Observable.just(DeleteThisUplink, last)
                    } else {
                        // No relevant change to the uplink port.
                        Observable.empty()
                    }
                } else {
                    // Uplink port has removed the IPv6 address, delete the
                    // uplink.
                    last = null
                    Observable.just(DeleteThisUplink)
                }
            }
        }

        /**
          * Handles any errors emitted by the notification observable.
          */
        private def handleErrors(t: Throwable): Observable[Notification] = {
            log.warn(s"Exception on uplink port $portId", t)
            Observable.empty()
        }

        /**
          * Emits cleanup notifications for the given [[Observer]], when the
          * port is deleted or an error is emitted.
          */
        private def cleanup(child: Observer[_ >: Notification]): Unit = {
            if ((currentPort ne null) && (currentPort.portAddress6 ne null)) {
                child onNext DeleteThisUplink
                child.onCompleted()
            }
        }

        /**
          * Returns the ports from the stateful port groups for the current
          * port. Calling this method assumes that the uplink port state is
          * ready where both the port and the corresponding port groups have
          * been loaded from the store.
          */
        private def uplinkPorts: JList[UUID] = {
            // We expect that most port will belong to at most one port group,
            // so we optimize this code to use a single port group.
            val portGroups = portGroupTracker.currentRefs
            if (portGroups.isEmpty) {
                NoPortGroupPortIds
            } else if (portGroups.size == 1 && portGroups.head._2.stateful) {
                portGroups.head._2.members
            } else if (portGroups.size == 1) {
                NoPortGroupPortIds
            } else {
                // We should rarely get here such that we need to aggregate
                // multiple stateful port groups.
                val portIds = new util.HashSet[UUID]()
                for (portGroup <- portGroups.values if portGroup.stateful) {
                    portIds.addAll(portGroup.members)
                }
                val array = portIds.toArray(new Array[UUID](portIds.size()))
                util.Arrays.sort(array, UUIDUtil.Comparator)
                ImmutableList.copyOf(array)
            }
        }
    }

}

/**
  * A trait that manages the VPP uplink ports. Upon starts, the trait
  * subscribes to the local ports observable to receive updates for the state
  * of the local ports. Upon each notification, the trait will consolidate
  * the updates and send the following messages to the [[VppExecutor]]:
  *
  * - [[org.midonet.midolman.vpp.VppUplink.AddUplink]] Emitted when a new
  *   IPv6 uplink port becomes active or whenever the port configuration
  *   changes in such a way that it needs to be reconfigured in VPP.
  * - [[org.midonet.midolman.vpp.VppUplink.DeleteUplink]] Emitted when a current
  *   uplink port becomes inactive, is deleted, or its configuration changes
  *   in such a way and that it needs to be reconfigured in VPP.
  *
  * Notes on synchronization: All processing is non I/O and it is done on the
  * virtual topology thread. Once the notifications from the virtual topology
  * are processed, the resulting [[Notification]] messages are sent to the
  * [[VppExecutor]] to be handled on the VPP conveyor belt, including
  * serializing their tasks and performing I/O operations.
  */
private[vpp] trait VppUplink {

    protected def vt: VirtualTopology

    protected def log: Logger

    private val started = new AtomicBoolean(false)
    private val uplinks = new mutable.HashMap[UUID, UplinkState]

    private val portsObserver = new Observer[LocalPortActive] {
        override def onNext(port: LocalPortActive): Unit = {
            vt.assertThread()
            if (port.active) {
                createUplink(port.portId)
            } else {
                deleteUplink(port.portId)
            }
        }

        override def onCompleted(): Unit = {
            log warn "Local ports observable completed unexpectedly"
        }

        override def onError(e: Throwable): Unit = {
            log.warn("Unhandled exception on local ports observable", e)
        }
    }
    private var portsSubscription: Subscription = _

    private val startRunnable = makeRunnable {
        if (portsSubscription ne null) {
            portsSubscription.unsubscribe()
        }
        portsSubscription = VirtualToPhysicalMapper.portsActive
                                                   .subscribe(portsObserver)
    }

    private val stopRunnable = makeRunnable {
        complete()
    }

    private val uplinkSubject = PublishSubject.create[Notification]

    /**
      * An [[Observable]] that emits notifications for the VPP uplinks. The
      * notifications are emitted on the virtual topology thread.
      */
    protected val uplinkObservable = uplinkSubject.asObservable()

    /**
      * Creates a new uplink observer for the specified uplink port.
      */
    private def uplinkObserver(portId: UUID) = new Observer[Notification] {
        override def onNext(notification: Notification): Unit = {
            log debug s"Uplink port $portId notification: $notification"
            uplinkSubject onNext notification
        }

        override def onCompleted(): Unit = {
            log debug s"Uplink port $portId deleted"
            uplinks.remove(portId)
        }

        override def onError(e: Throwable): Unit = {
            // We should never get here since the uplink state must handle all
            // errors.
            log.error(s"Unhandled exception on uplink port $portId", e)
            uplinks.remove(portId)
        }
    }

    /**
      * Starts monitoring the uplink ports.
      */
    protected def startUplink(): Unit = {
        log debug s"Start monitoring VPP uplinks"

        if (started.compareAndSet(false, true)) {
            log debug s"Subscribing to local active ports notifications"
            // Submit a start task on the VT thread (needed for synchronization).
            val startFuture = vt.vtExecutor.submit(startRunnable)

            // Wait on the start to complete.
            try startFuture.get(ChildActorStartTimeout.toMillis,
                                TimeUnit.MILLISECONDS)
            catch {
                case e: TimeoutException =>
                    startFuture.cancel(false)
                    log warn "Starting uplinks timed out"
                case NonFatal(e) =>
                    log.warn("Unhandled exception when starting uplinks", e)
            }
        }
    }

    /**
      * Stops monitoring the downlink ports.
      */
    protected def stopUplink(): Unit = {
        log debug s"Stop monitoring VPP uplinks"

        if (started.compareAndSet(true, false)) {
            // Submit a stop task on the VT thread (needed for synchronization).
            val stopFuture = vt.vtExecutor.submit(stopRunnable)

            // Wait on the stop to complete.
            try stopFuture.get(ChildActorStopTimeout.toMillis,
                               TimeUnit.MILLISECONDS)
            catch {
                case e: TimeoutException =>
                    stopFuture.cancel(false)
                    log warn "Stopping uplinks timed out"
                case NonFatal(e) =>
                    log.warn("Unhandled exception when stopping uplinks", e)
            }
        }
    }

    @NotThreadSafe
    protected def uplinkError(portId: UUID, e: Throwable): Unit = {
        log.warn(s"Configuring uplink $portId failed: removing", e)
        vt.assertThread()
        deleteUplink(portId)
    }

    /**
      * Creates the state for a new uplink port and begins emitting setup
      * notifications for that port.
      */
    private def createUplink(portId: UUID): Unit = {
        uplinks.getOrElseUpdate(portId, {
            log debug s"Local uplink $portId became active"
            val state = new UplinkState(portId, vt, log)
            state.observable.subscribe(uplinkObserver(portId))
            state
        })
    }

    /**
      * Deletes the state for an existing uplink port. Upon deletion, the
      * state will emit any cleanup notifications for the existing port.
      */
    private def deleteUplink(portId: UUID): Unit = {
        uplinks.remove(portId) match {
            case Some(state) => state.complete()
            case None => log debug s"No uplink port $portId"
        }
    }

    /**
      * Unsubscribes from all notifications. The method will complete the
      * [[UplinkState]] for all current uplink ports, which upon completion
      * will emit the necessary cleanup notification.
      */
    private def complete(): Unit = {
        if (portsSubscription ne null) {
            portsSubscription.unsubscribe()
            portsSubscription = null
        }

        for (state <- uplinks.values) {
            state.complete()
        }
        uplinks.clear()
    }
}
