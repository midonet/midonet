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

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.{TimeUnit, TimeoutException}
import java.util.{UUID, BitSet => JBitSet}

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.util.control.NonFatal

import akka.actor.Actor

import rx.Observable.OnSubscribe
import rx.schedulers.Schedulers
import rx.subjects.PublishSubject
import rx.{Observable, Observer, Subscriber, Subscription}

import org.midonet.cluster.data.ZoomConvert
import org.midonet.cluster.data.storage.StateTable.Update
import org.midonet.cluster.data.storage.StateTableEncoder.Fip64Encoder.DefaultValue
import org.midonet.cluster.data.storage.model.Fip64Entry
import org.midonet.cluster.models.Topology.{Rule => TopologyRule}
import org.midonet.cluster.services.MidonetBackend
import org.midonet.midolman.rules.{Nat64Rule, NatTarget, Rule}
import org.midonet.midolman.services.MidolmanActorsService.{ChildActorStartTimeout, ChildActorStopTimeout}
import org.midonet.midolman.simulation.RouterPort
import org.midonet.midolman.topology.{StoreObjectReferenceTracker, VirtualTopology}
import org.midonet.midolman.vpp.VppDownlink.{DownlinkState, Notification}
import org.midonet.packets.{IPv4Addr, IPv4Subnet, IPv6Addr, IPv6Subnet}
import org.midonet.util.functors.{makeAction0, makeFunc1, makeRunnable}
import org.midonet.util.logging.Logger

object VppDownlink {

    trait Notification { def portId: UUID }

    /**
      * A message to create a veth pair for the specified virtual port. This is
      * the port on the tenant router used for IPv6 traffic for all the floating
      * IPs associated with this tenant router. Upon receiving this message,
      * the [[VppController]] must create a veth pair, where one interface is
      * called `vpp-[portId]` and the other `ovs-[portId]`. The VPP interface
      * is configured with a link-local IP address from the same subnet as the
      * router port (169.254.x.y/30).
      *
      * The message also includes the following: (i) the router port IPv6
      * address, (ii) the XLAT-ed IPv4 subnet configured for this tenant router
      * port and (ii) the L4 port range.
      */
    case class CreateDownlink(portId: UUID,
                              vrfTable: Int,
                              portAddress4: IPv4Subnet,
                              portAddress6: IPv6Subnet,
                              natPool: NatTarget) extends Notification {

        lazy val vppAddress4 = new IPv4Subnet(portAddress4.getIntAddress + 1,
                                              portAddress4.getPrefixLen)

        override def toString: String =
            s"CreateDownlink [port=$portId vrf=$vrfTable " +
            s"portAddress4=$portAddress4 portAddress6=$portAddress6 " +
            s"vppAddress4=$vppAddress4 pool=$natPool]"
    }

    /**
      * A message to update the IPv6 address of a downlink port. The other
      * parameters of the downlink port (IPv4 address, XLAT-ed IPv4 address
      * and L4 port range) are immutable.
      */
    case class UpdateDownlink(portId: UUID, vrfTable: Int,
                              oldAddress: IPv6Subnet,
                              newAddress: IPv6Subnet) extends Notification {

        override def toString: String =
            s"UpdateDownlink [port=$portId vfr=$vrfTable oldAddress=$oldAddress " +
            s"newAddress=$newAddress]"
    }

    /**
      * A message to delete the veth pair for the tenant router downlink port.
      */
    case class DeleteDownlink(portId: UUID, vrfTable: Int) extends Notification {

        override def toString: String =
            s"DeleteDownlink [port=$portId vfr=$vrfTable]"
    }

    /**
      * A message that instructs the VPP controller to install a NAT64
      * translation rule for a new floating IP.
      */
    case class AssociateFip(portId: UUID, vrfTable: Int, floatingIp: IPv6Addr,
                            fixedIp: IPv4Addr, localIp: IPv4Subnet,
                            natPool: NatTarget) extends Notification {

        override def toString: String =
            s"AssociateFip [port=$portId vrf=$vrfTable floatingIp=$floatingIp " +
            s"fixedIp=$fixedIp localIp=$localIp natPool=$natPool]"
    }

    /**
      * A message that instructs the VPP controller to remove a NAT64
      * translation rule for an existing floating IP.
      */
    case class DisassociateFip(portId: UUID, vrfTable: Int, floatingIp: IPv6Addr,
                               fixedIp: IPv4Addr, localIp: IPv4Subnet)
        extends Notification {

        override def toString: String =
            s"DisassociateFip [port=$portId vrf=$vrfTable " +
            s"floatingIp=$floatingIp fixedIp=$fixedIp localIp=$localIp]"
    }

    /**
      * Maintains the state for a downlink port, which includes monitoring the
      * downlink port of the tenant router for changes to its IPv6 address,
      * and maintaining the list of FIP64 entries.
      */
    private class DownlinkState(val portId: UUID, val vrfTable: Int,
                                vt: VirtualTopology, log: Logger) {

        private val fips = new mutable.HashSet[Fip64Entry]

        private var currentPort: RouterPort = _
        private var currentRule: Nat64Rule = _

        private val ruleTracker = new StoreObjectReferenceTracker[TopologyRule](
            vt, classOf[TopologyRule], log)

        private val subject = PublishSubject.create[Notification]()
        private val mark = PublishSubject.create[RouterPort]()

        private val portObservable = VirtualTopology
            .observable(classOf[RouterPort], portId)
            .takeUntil(mark)
            .doOnCompleted(makeAction0(portDeleted()))
            .flatMap[Notification](makeFunc1(portUpdated))

        private val ruleObservable = ruleTracker.refsObservable
            .flatMap[Notification](makeFunc1(ruleUpdated))

        private val portCleanup: Observable[Notification] = {
            Observable.create(new OnSubscribe[Notification] {
                override def call(child: Subscriber[_ >: Notification]): Unit = {
                    cleanup(child)
                }
            })
        }

        /**
          * An observable that emits [[Notification]] events for changes in the
          * underlying downlink port and corresponding FIP NAT rules.
          */
        val observable: Observable[Notification] = Observable
            .merge(subject, ruleObservable, portObservable)
            .filter(makeFunc1(_ => isReady))
            .onErrorResumeNext(makeFunc1(handleErrors))
            .concatWith(portCleanup)

        /**
          * Inserts an [[AssociateFip]] notification.
          */
        def addFip(fip: Fip64Entry): Unit = {
            require(fip.portId == portId)
            if (fips.add(fip) && isReady) {
                subject onNext AssociateFip(portId, vrfTable, fip.floatingIp,
                                            fip.fixedIp,
                                            currentPort.portAddress4,
                                            currentRule.natPool)
            }
        }

        /**
          * Inserts an [[DisassociateFip]] notification.
          */
        def removeFip(fip: Fip64Entry): Unit = {
            require(fip.portId == portId)
            if (fips.remove(fip) && isReady) {
                subject onNext DisassociateFip(portId, vrfTable, fip.floatingIp,
                                               fip.fixedIp,
                                               currentPort.portAddress4)
            }
        }

        /**
          * Completes the observable exposed by this [[DownlinkState]]. Before
          * completing, the observable will emit any necessary cleanup
          * notifications.
          */
        def complete(): Unit = {
            mark.onCompleted()
        }

        /**
          * Indicates whether the state has received the downlink port and FIP64
          * rule and is ready to emit FIP64 [[Notification]]s.
          */
        @inline def isReady: Boolean = {
            (currentPort ne null) && (currentRule ne null)
        }

        /**
          * Returns true if this downlink is not associated with any floating IP.
          */
        @inline def isEmpty: Boolean = {
            fips.isEmpty
        }

        /**
          * Handles the port deletion, by completing the output observable.
          */
        private def portDeleted(): Unit = {
            log debug s"Port $portId deleted"
            subject onCompleted()
            ruleTracker.completeRefs()
        }

        /**
          * Handles updates for the downlink the port. The method updates the
          * tracked FIP NAT rules, and if both the port and the rule are ready,
          * it will emit a [[CreateDownlink]] notification.
          */
        private def portUpdated(port: RouterPort): Observable[Notification] = {
            log debug s"Port updated: $port"

            val natRuleIds = Set(port.fipNatRules.asScala.map(_.id): _*)

            // Track the current FIP NAT rules.
            ruleTracker.requestRefs(natRuleIds)

            val result: Observable[Notification] =
                if ((currentPort eq null) && (currentRule ne null)) {
                    // If this is the first port notification, and the FIP64 NAT
                    // rule has been loaded, notify the port creation.
                    initialize(port, currentRule)
                } else {
                    // Either the port or the rule is not ready: emit nothing.
                    Observable.empty()
                }

            currentPort = port

            result
        }

        /**
          * Handles updates for the port FIP NAT rules. Normally, the downlink
          * ports should have only one FIP NAT rule, however here we also handle
          * abnormal cases.
          */
        private def ruleUpdated(r: TopologyRule): Observable[Notification] = {
            val rule = ZoomConvert.fromProto(r, classOf[Rule])

            log debug s"FIP NAT rule updated: $rule"

            rule match {
                case fipRule: Nat64Rule =>
                    if (currentRule eq null) {
                        // This is the first FIP64 rule.
                        log debug s"Port $portId has NAT64 rule ${rule.id}"
                        currentRule = fipRule
                        if (currentPort ne null) {
                            // If the port was loaded, emit a create
                            // notification.
                            initialize(currentPort, fipRule)
                        } else {
                            Observable.empty()
                        }
                    } else if (currentRule.id == fipRule.id) {
                        // The FIP64 rule is updated.
                        log debug s"Port $portId NAT64 rule ${rule.id} updated"
                        val oldRule = currentRule
                        currentRule = fipRule
                        if ((currentPort ne null) &&
                            fipRule.portAddress != oldRule.portAddress) {
                            // If the port IPv6 has changed, emit an update
                            // notification.
                            log debug s"Port $portId rule ${rule.id} changed " +
                                      s"port address from ${oldRule.portAddress} " +
                                      s"to ${fipRule.portAddress}"
                            Observable.just(UpdateDownlink(
                                portId, vrfTable, oldRule.portAddress,
                                fipRule.portAddress))
                        } else {
                            Observable.empty()
                        }
                    } else {
                        // We do not support updating the FIP64 rule, once a
                        // rule was loaded for a downlink port, that rule
                        // remains in force until the port is deleted.
                        log warn s"Downlink port has multiple FIP NAT rules " +
                                 s"${currentRule.id} and ${fipRule.id}: " +
                                 s"ignoring the second"
                        Observable.empty()
                    }
                case _ =>
                    Observable.empty() // Ignore all other rules.
            }
        }

        /**
          * Handles any errors emitted by the notification observable.
          */
        private def handleErrors(t: Throwable): Observable[Notification] = {
            log.warn(s"Exception on downlink port $portId", t)
            Observable.empty()
        }

        /**
          * Returns an observable that emits the initial set of notifications
          * for this downlink port, which includes a [[CreateDownlink]] and
          * an [[AssociateFip]] for every floating IP that has been added.
          */
        private def initialize(port: RouterPort, rule: Nat64Rule)
        : Observable[Notification] = {
            val notifications = new Array[Notification](fips.size + 1)
            notifications(0) = CreateDownlink(portId, vrfTable,
                                              currentPort.portAddress4,
                                              rule.portAddress,
                                              rule.natPool)
            var index = 1
            val iterator = fips.iterator
            while (iterator.hasNext) {
                val fip = iterator.next()
                notifications(index) = AssociateFip(portId, vrfTable,
                                                    fip.floatingIp,
                                                    fip.fixedIp,
                                                    currentPort.portAddress4,
                                                    currentRule.natPool)
                index += 1
            }
            Observable.from(notifications)
        }

        /**
          * Emits cleanup notification for the given [[Observer]], when the
          * port is deleted or an error is emitted.
          */
        private def cleanup(child: Observer[_ >: Notification]): Unit = {
            if ((currentPort ne null) && (currentRule ne null)) {
                val iterator = fips.iterator
                while (iterator.hasNext) {
                    val fip = iterator.next()
                    child onNext DisassociateFip(portId, vrfTable,
                                                 fip.floatingIp,
                                                 fip.fixedIp,
                                                 currentPort.portAddress4)
                }
                child onNext DeleteDownlink(portId, vrfTable)
                child.onCompleted()
            }
        }
    }

}

/**
  * A trait that manages the VPP downlink ports. Upon start, the trait
  * subscribes to the global FIP64 table to receive updates for the current
  * floating IPv6 addresses. Upon each notification, the trait will consolidate
  * the updates and send the following messages to the actor:
  *
  * - [[org.midonet.midolman.vpp.VppDownlink.CreateDownlink]] Emitted when a
  *   new downlink port is added with a FIP.
  * - [[org.midonet.midolman.vpp.VppDownlink.UpdateDownlink]] Emitted when the
  *   IPv6 address of a downlink port changes.
  * - [[org.midonet.midolman.vpp.VppDownlink.DeleteDownlink]] Emitted when a
  *   downlink port is deleted.
  * - [[org.midonet.midolman.vpp.VppDownlink.AssociateFip]] Emitted when a new
  *   floating IPv6 is associated with a downlink port.
  * - [[org.midonet.midolman.vpp.VppDownlink.DisassociateFip]] Emitted when a
  *   floating IPv6 is disassociated from a downlink port.
  *
  * Notes on synchronization: All processing is non I/O and it is done on the
  * virtual topology thread. Once the notification from the virtual topology
  * are processed, the resulting [[Notification]] messages are sent to the
  * actor, where the actor will act upon them, including serializing their tasks
  * using the conveyor belt and performing I/O operations.
  */
private[vpp] trait VppDownlink { this: Actor =>

    protected def vt: VirtualTopology

    protected def log: Logger

    private val started = new AtomicBoolean(false)
    private val downlinks = new mutable.HashMap[UUID, DownlinkState]
    private val scheduler = Schedulers.from(vt.vtExecutor)

    // We pre-allocate the VRF bit set with for up to 16,384 downlink ports.
    // VRF 0 is reserved.
    private val vrfs = new JBitSet(0x4000)
    vrfs.set(0)

    private val tableObserver = new Observer[Update[Fip64Entry, AnyRef]] {
        override def onNext(update: Update[Fip64Entry, AnyRef]): Unit = {
            update match {
                case Update(entry, null, DefaultValue) =>
                    addFip(entry)
                case Update(entry, DefaultValue, null) =>
                    removeFip(entry)
                case Update(_,_,_) =>
                    log warn s"Unexpected FIP64 update"
            }
        }

        override def onCompleted(): Unit = {
            log warn "Unexpected completion of the FIP64 table notification " +
                     "stream"
            complete()
        }

        override def onError(e: Throwable): Unit = {
            log.error("Unhandled exception on the FIP64 table notification " +
                      "stream", e)
            complete()
        }
    }
    private var tableSubscription: Subscription = _

    private val fip64Table = vt.backend.stateTableStore
        .getTable[Fip64Entry, AnyRef](MidonetBackend.Fip64Table)

    private val startRunnable = makeRunnable {
        if (tableSubscription ne null) {
            tableSubscription.unsubscribe()
        }
        tableSubscription = fip64Table.observable.observeOn(scheduler)
                                      .subscribe(tableObserver)
    }
    private val stopRunnable = makeRunnable { complete() }

    /**
      * Creates a new downlink observer for the specified downlink port.
      */
    private def downlinkObserver(portId: UUID) = new Observer[Notification] {
        override def onNext(notification: Notification): Unit = {
            log.debug(s"Downlink port $portId notification: $notification")
            self ! notification
        }

        override def onCompleted(): Unit = {
            log.debug(s"Downlink port $portId deleted")
            val state = downlinks.remove(portId)
            if (state.isDefined) {
                vrfs.clear(state.get.vrfTable)
            }
        }

        override def onError(e: Throwable): Unit = {
            // We should never get here since the downlink state must handle
            // all errors.
            log.error(s"Unhandled exception on downlink port $portId", e)
            val state = downlinks.remove(portId)
            if (state.isDefined) {
                vrfs.clear(state.get.vrfTable)
            }
        }
    }

    /**
      * Starts monitoring the downlink ports.
      */
    protected def startDownlink(): Unit = {
        log debug s"Start monitoring VPP downlinks"

        if (started.compareAndSet(false, true)) {
            log debug s"Subscribing to FIP64 table"
            // Submit a start task on the VT thread (needed for synchronization).
            val startFuture = vt.vtExecutor.submit(startRunnable)

            // Wait on the start to complete.
            // Wait on the stop to complete.
            try startFuture.get(ChildActorStartTimeout.toMillis,
                                TimeUnit.MILLISECONDS)
            catch {
                case e: TimeoutException =>
                    startFuture.cancel(false)
                    log warn "Starting FIP64 downlinks timed out"
                case NonFatal(e) =>
                    log.warn("Unhandled exception when starting FIP64 " +
                             "downlinks", e)
            }
        }
    }

    /**
      * Stops monitoring the downlink ports.
      */
    protected def stopDownlink(): Unit = {
        log debug s"Stop monitoring VPP downlinks"

        if (started.compareAndSet(true, false)) {
            // Submit a stop task on the VT thread (needed for synchronization).
            val stopFuture = vt.vtExecutor.submit(stopRunnable)

            // Wait on the stop to complete.
            try stopFuture.get(ChildActorStopTimeout.toMillis,
                               TimeUnit.MILLISECONDS)
            catch {
                case e: TimeoutException =>
                    stopFuture.cancel(false)
                    log warn "Stopping FIP64 downlinks timed out"
                case NonFatal(e) =>
                    log.warn("Unhandled exception when stopping FIP64 " +
                             "downlinks", e)
            }
        }
    }

    /**
      * Adds a new [[Fip64Entry]]. The method gets or creates a new
      * [[DownlinkState]] for the corresponding port, which will track the
      * floating IPs for that port, and will fetch necessary port and FIP64
      * rule data.
      */
    private def addFip(fip: Fip64Entry): Unit = {
        log debug s"FIP64 entry added: $fip"
        getOrCreateDownlink(fip).addFip(fip)

    }

    /**
      * Removes an existing [[Fip64Entry]] for a downlink port. If this is the
      * last floating IP for the downlink port, the method will call `complete`
      * on the corresponding [[DownlinkState]], which will remove the downlink.
      */
    private def removeFip(fip: Fip64Entry): Unit = {
        log debug s"FIP64 entry deleted: $fip"
        downlinks.get(fip.portId) match {
            case Some(downlink) =>
                downlink.removeFip(fip)
                if (downlink.isEmpty) {
                    downlink.complete()
                    downlinks.remove(downlink.portId)
                }
            case None =>
                log warn s"No existing downlink port for FIP64 entry $fip"
        }
    }

    /**
      * @return The downlink state for the specified FIP64 entry, or it creates
      *         a new state if it does not exist
      */
    private def getOrCreateDownlink(entry: Fip64Entry): DownlinkState = {
        downlinks.getOrElseUpdate(entry.portId, {
            // Allocate a VRF index.
            val vrf = vrfs.nextClearBit(0)
            vrfs.set(vrf)

            log debug s"Subscribing to downlink port ${entry.portId} with " +
                      s"VRF $vrf"

            val state = new DownlinkState(entry.portId, vrf, vt, log)
            state.observable.subscribe(downlinkObserver(entry.portId))
            state
        })
    }

    /**
      * Unsubscribes from all notifications. The method will complete the
      * [[DownlinkState]] for all current downlink ports, which upon completion
      * will emit the necessary cleanup notifications.
      */
    private def complete(): Unit = {
        if (tableSubscription ne null) {
            tableSubscription.unsubscribe()
            tableSubscription = null
        }

        for (state <- downlinks.values) {
            state.complete()
        }
        downlinks.clear()
    }

}
