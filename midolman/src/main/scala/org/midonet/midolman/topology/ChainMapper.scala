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
import java.util.concurrent.atomic.AtomicBoolean

import scala.collection.mutable
import scala.collection.JavaConversions._

import rx.Observable
import rx.subjects.{PublishSubject, BehaviorSubject}

import org.midonet.cluster.models.Topology.{Rule, Chain}
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.midolman.rules.{Rule => SimRule}
import org.midonet.midolman.simulation.{Chain => SimChain}
import org.midonet.midolman.topology.ChainMapper.{RuleState, ChainState}
import org.midonet.util.functors.{makeAction0, makeFunc1}

object ChainMapper {

    private final class ChainState {
        var chain: Chain = _
        val rules = new mutable.LinkedHashMap[UUID, SimRule]
        val jumpTargets = new mutable.HashMap[UUID, SimChain]
    }

    private final class RuleState(ruleId: UUID, vt: VirtualTopology) {
        private val mark = PublishSubject.create[Rule]()
        val observable = vt.store.observable[Rule](classOf[Rule], ruleId)
            .takeUntil(mark)
        def complete() = mark.onCompleted()
    }
}

sealed class ChainMapper(id: UUID, vt: VirtualTopology)
        extends VirtualDeviceMapper[SimChain](id, vt) {

    override def logSource = s"org.midonet.devices.chain.chain-$id"

    private val observableCreated = new AtomicBoolean(false)
    private var outStream: Observable[SimChain] = _
    private val chainState = new ChainState
    private val ruleStream = BehaviorSubject.create[Observable[Rule]]()
    private val rules = new mutable.HashMap[UUID, RuleState]()

    private def handleUpdate(update: AnyRef): SimChain = update match {
        case chain: Chain =>
            // Subscribe to all rules we are not subscribed to yet
            chain.getRouterIdsList.filterNot(chainState.rules.containsKey)
                .foreach(ruleId => {
                    rules(ruleId) = new RuleState(ruleId, vt)
                    ruleStream.onNext(rules(ruleId).observable)
                })

            // Unsubscribe to rules that are not part of the chain anymore
            chainState.chain.getRuleIdsList.toSeq.diff(chain.getRouterIdsList)
                .foreach(ruleId => {
                    rules(ruleId).complete()
                    rules.remove(ruleId)
                })
            chainState.chain = chain
            new SimChain(id, chainState.rules.values.toList, chainState.jumpTargets,
                         chainState.chain.getName)

        case rule: Rule =>
            val simRule = SimRule.fromProto(rule)
            chainState.rules(rule.getId) = simRule
            new SimChain(id, chainState.rules.values.toList, chainState.jumpTargets,
                         chainState.chain.getName)

        case _ => throw new IllegalArgumentException("ChainMapper does not support" +
                                                     " updates of type: " +
                                                     s"${update.getClass.getName}")
    }

    //TODO(nicolas): Figure out a way to perform this check using the
    //               simChain while doing that in an efficient manner.
    private def chainReady(simChain: SimChain): Boolean =
        chainState.chain.getRuleIdsList.forall(chainState.rules.contains(_)) &&
        chainState.chain.getRuleIdsList.forall(chainState.jumpTargets.contains(_))

    private def onChainCompleted = makeAction0({
        ruleStream.onCompleted()
        rules.values.foreach(_.complete())
        rules.clear()
    })

    private val chainObservable = vt.store.observable(classOf[Chain], id)
        .doOnCompleted(onChainCompleted)

    protected override def observable = {
        if (observableCreated.compareAndSet(false, true)) {
            outStream = Observable.merge[AnyRef](chainObservable,
                                                 Observable.merge(ruleStream))
                .map[SimChain](makeFunc1(handleUpdate))
                .filter(makeFunc1(chainReady))
        }
        outStream
    }
}
