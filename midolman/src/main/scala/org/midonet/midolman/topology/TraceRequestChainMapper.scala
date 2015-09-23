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

package org.midonet.midolman.topology

import java.util.UUID

import scala.collection.JavaConverters._
import scala.collection.mutable

import rx.Observable
import rx.subjects.{BehaviorSubject, PublishSubject, Subject}

import org.midonet.cluster.data.ZoomConvert
import org.midonet.cluster.models.Topology.TraceRequest
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.midolman.rules.{Condition, TraceRule, Rule}
import org.midonet.midolman.simulation.Chain
import org.midonet.midolman.topology.VirtualTopology.VirtualDevice
import org.midonet.midolman.topology.devices.TraceChain
import org.midonet.util.functors._

/**
  * Trait for devices which can have trace requests.
  * when the trace request topology object is loaded from the underlying
  * store, requestTraceChain is called with the id of the existing
  * infilter chain and the list of trace requests for that device.
  *
  * After processing, an Option[UUID] will be emitted from
  * traceChainObservable with the id of chain which should be used as the
  * infilter of the device. If traces are enabled, this will be a trace chain.
  * If there are no traces, this will be None.
  *
  * In the case of trace chains, the chain will not exist in the underlying
  * store but in a map which the chain mapper has access to. When the chain
  * mapper looks up the chain and doesn't find it, it when looks for it in this
  * trace chain map.
  *
  * Device mappers should call isTracingReady before building a device to
  * ensure all trace chain processing has been done.
  */
trait TraceRequestChainMapper[D <: VirtualDevice] {
    this: VirtualDeviceMapper[D] =>

    val traceChainId = UUID.randomUUID

    var traceRulesObs = BehaviorSubject.create[Observable[TraceRequest]]()
    var retriggerObs = BehaviorSubject.create[Observable[Any]]()

    private var awaitingTraceChain = false
    var traceRequests: mutable.Map[UUID, TraceRequestState] = mutable.Map()

    def traceChainMap: mutable.Map[UUID,Subject[Chain,Chain]]

    class TraceRequestState(id: UUID) {
        private val mark = PublishSubject.create[TraceRequest]()
        val observable = vt.store.observable(classOf[TraceRequest], id)
            .observeOn(vt.vtScheduler)
            .takeUntil(mark)
            .map[TraceRequest](makeFunc1(makeRule))

        def makeRule(tr: TraceRequest): TraceRequest = {
            val condition = ZoomConvert.fromProto(tr.getCondition,
                                                  classOf[Condition])
            if (tr.getEnabled) {
                rule = new TraceRule(tr.getId, condition, tr.getLimit,
                                     traceChainId)
                enabled = true
            } else {
                enabled = false
            }
            tr
        }

        def complete() = {
            mark.onCompleted()
        }

        var rule: TraceRule = null
        var enabled = true

        def getRule: Option[Rule] = {
            if (enabled) Some(rule) else None
        }
        def isReady = rule != null || !enabled
    }

    def requestTraceChain(traceIds: List[UUID]) = {
        val added = mutable.Buffer[TraceRequestState]()
        for (trace <- traceIds if !traceRequests.contains(trace)) {
            val state = new TraceRequestState(trace)
            added += state
            traceRequests += (trace -> state)
        }

        var removed = 0
        for (trace <- traceRequests.keys if !traceIds.contains(trace)) {
            traceRequests.remove(trace).foreach(_.complete())
            removed += 1
        }

        for (state <- added) {
            traceRulesObs onNext state.observable
        }

        awaitingTraceChain = added.nonEmpty || removed > 0

        // we need to send something to observable to retrigger
        // the chain generation in the case that no new trace requests
        // have been added, and some old trace requests have been removed
        // If not, the chain would not be updated to reflect these changes
        if (added.isEmpty && removed > 0) {
            retriggerObs onNext Observable.just(null)
        }
    }

    private def removeFromMap(): Unit = {
        traceChainMap.remove(traceChainId) match {
            case Some(p) => p.onCompleted()
            case None =>
        }
    }

    def completeTraceChain(): Unit = {
        removeFromMap()
        traceRequests.foreach(_._2.complete())
        retriggerObs.onCompleted()
        traceRulesObs.onCompleted()
    }

    def isTracingReady: Boolean = {
        log.info(s"Trace chain ready: ${!awaitingTraceChain}")
        !awaitingTraceChain
    }

    private def isReadyToBuild(update: Any): Boolean = traceRequests.forall(_._2.isReady)

    private def makeTraceChain(update: Any): TraceChain = {
        new TraceChain(traceChainId,
                       traceRequests.flatMap(_._2.getRule).toList.asJava)
    }

    private def publishAndReturnId(chain: TraceChain): Option[UUID] = {
        awaitingTraceChain = false
        if (chain.hasTracesEnabled) {
            traceChainMap.get(chain.getId) match {
                case Some(p) => p onNext chain
                case None =>
                    val subject = BehaviorSubject.create[Chain](chain)
                    traceChainMap += (chain.getId -> subject)
            }
            Some(chain.getId)
        } else {
            removeFromMap()
            None
        }
    }

    protected lazy val traceChainObservable =
        Observable.merge[Any](Observable.merge(retriggerObs),
                              Observable.merge(traceRulesObs))
            .observeOn(vt.vtScheduler)
            .filter(makeFunc1(isReadyToBuild))
            .map[TraceChain](makeFunc1(makeTraceChain))
            .map[Option[UUID]](makeFunc1(publishAndReturnId))
}

