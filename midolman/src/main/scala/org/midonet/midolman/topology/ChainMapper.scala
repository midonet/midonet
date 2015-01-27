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
import org.midonet.midolman.rules.{Rule => SimRule, JumpRule}
import org.midonet.midolman.simulation.{Chain => SimChain}
import org.midonet.midolman.topology.ChainMapper.{JumpChainState, RuleState, ChainState}
import org.midonet.util.functors.{makeAction0, makeFunc1}

object ChainMapper {
    private final class ChainState {
        var chainProto: Chain = Chain.newBuilder.build()
        val rules = new mutable.LinkedHashMap[UUID, SimRule]
        val jumpTargets = new mutable.HashMap[UUID, SimChain]
    }

    private final class JumpChainState(chainId: UUID) {
        private val mark = PublishSubject.create[SimChain]()
        val observable = VirtualTopology.observable[SimChain](chainId)
            .takeUntil(mark)
        def complete() = mark.onCompleted()
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

    // The stream of rules that belong to this chain
    private val ruleStream = BehaviorSubject.create[Observable[Rule]]()
    private val rules = new mutable.HashMap[UUID, RuleState]()

    // The stream of chains pointed to by jump rules of this chain
    private val chainStream = BehaviorSubject.create[Observable[SimChain]]()
    private val jumpChains = new mutable.HashMap[UUID, JumpChainState]()

    private def handleUpdate(update: AnyRef): SimChain = update match {
        case chain: Chain =>
            // Subscribe to all rules we are not subscribed to yet.
            chain.getRuleIdsList.filterNot(chainState.rules.contains(_))
                .foreach(ruleId => {
                    rules(ruleId) = new RuleState(ruleId, vt)
                    ruleStream.onNext(rules(ruleId).observable)
                })

            // Unsubscribe to rules that are not part of the chain anymore.
            chainState.chainProto.getRuleIdsList.diff(chain.getRuleIdsList)
                .foreach(ruleId => {
                    rules(ruleId).complete()
                    rules.remove(ruleId)

                    // If it is a jump rule, unsubscribe from the chain the rule
                    // points to.
                    if (chainState.rules(ruleId).isInstanceOf[JumpRule]) {
                        val jumpChainId = chainState.rules(ruleId)
                            .asInstanceOf[JumpRule].jumpToChainID
                        jumpChains(jumpChainId).complete()
                        jumpChains.remove(jumpChainId)
                        chainState.jumpTargets.remove(jumpChainId)
                    }
                    chainState.rules.remove(ruleId)
                })
            chainState.chainProto = chain
            new SimChain(id, chainState.rules.values.toList,
                         chainState.jumpTargets, chainState.chainProto.getName)

        case simChain: SimChain =>
            // This is an update of a chain pointed to by a jump rule.
            chainState.jumpTargets(simChain.id) = simChain
            new SimChain(id, chainState.rules.values.toList,
                         chainState.jumpTargets, chainState.chainProto.getName)

        case rule: Rule =>
            if (rule.hasJumpTo && !jumpChains.contains(rule.getJumpTo)) {
                // Subscribe to the chain this jump rule points to.
                val jumpChainId = rule.getJumpTo
                jumpChains(jumpChainId) = new JumpChainState(jumpChainId)
                chainStream.onNext(jumpChains(jumpChainId).observable)
            }
            val simRule = SimRule.fromProto(rule)
            chainState.rules(rule.getId) = simRule

            new SimChain(id, chainState.rules.values.toList,
                         chainState.jumpTargets, chainState.chainProto.getName)

        case _ => throw new IllegalArgumentException("ChainMapper does not support" +
                                                     " updates of type: " +
                                                     s"${update.getClass.getName}")
    }

    private def chainReady(simChain: SimChain): Boolean =
        // Since a simChain contains a list of rules, we do not rely on the chain itself to
        // perform this check but rather on the chainState.rules and jumpChains hash maps.
        // This is done for performance reasons.
        rules.keys.forall(chainState.rules.contains(_)) &&
        jumpChains.keys.forall(chainState.jumpTargets.contains(_))

    private def onChainCompleted = makeAction0({
        ruleStream.onCompleted()
        rules.values.foreach(_.complete())
        rules.clear()
        chainStream.onCompleted()
        jumpChains.values.foreach(_.complete())
        jumpChains.clear()
    })

    private val chainObservable = vt.store.observable(classOf[Chain], id)
        .doOnCompleted(onChainCompleted)

    protected override def observable: Observable[SimChain] = {
        if (observableCreated.compareAndSet(false, true)) {
            outStream = Observable.merge[AnyRef](chainObservable,
                                                 Observable.merge(ruleStream),
                                                 Observable.merge(chainStream))
                .map[SimChain](makeFunc1(handleUpdate))
                .filter(makeFunc1(chainReady))
        }
        outStream
    }
}
