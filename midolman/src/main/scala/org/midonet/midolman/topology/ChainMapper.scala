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

import scala.collection.mutable
import scala.collection.JavaConversions._

import rx.Observable
import rx.subjects.PublishSubject

import org.midonet.cluster.data.ZoomConvert
import org.midonet.cluster.models.Topology.{Rule, Chain}
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.midolman.logging.MidolmanLogging
import org.midonet.midolman.rules.{Rule => SimRule, JumpRule}
import org.midonet.midolman.simulation.{Chain => SimChain}
import org.midonet.midolman.topology.ChainMapper.{JumpChainState, RuleState, ChainState}
import org.midonet.util.functors.{makeAction0, makeFunc1}

object ChainMapper {

    /**
     * Stores the state for the chain we are providing an observable for.
     */
    private final class ChainState {
        var chainProto: Chain = Chain.newBuilder.build()
        val rules = new mutable.LinkedHashMap[UUID, SimRule]
        val jumpTargets = new mutable.HashMap[UUID, SimChain]
    }

    /**
     * Stores the state for a chain one of the rules of this chain points to.
     * If the rule starts pointing to another chain or if the rule is deleted,
     * we unsubscribe from the chain by calling the complete() method below.
     *
     * @param chainId The id of the chain one of the rules points to.
     */
    private final class JumpChainState(chainId: UUID) {
        private val mark = PublishSubject.create[SimChain]()
        // All observables coming from the VirtualTopology have their
        // notifications scheduled on the VirtualTopology thread.
        val observable = VirtualTopology.observable[SimChain](chainId)
            .takeUntil(mark)
        def complete() = mark.onCompleted()
    }

    /**
     * Stores the state for a rule. As for chains above, if a rule is removed
     * from the chain we are providing an observable for, we unsubscribe from
     * it by calling the complete() method below.
     *
     * @param ruleId The id of the rule we want to start observing.
     * @param vt The virtual topology object.
     */
    private final class RuleState(ruleId: UUID, vt: VirtualTopology) {
        private val mark = PublishSubject.create[Rule]()
        val observable = vt.store.observable[Rule](classOf[Rule], ruleId)
            .takeUntil(mark)
            .observeOn(vt.scheduler)
        def complete() = mark.onCompleted()
    }
}

sealed class ChainMapper(id: UUID, vt: VirtualTopology)
                                   extends VirtualDeviceMapper[SimChain](id, vt)
                                   with MidolmanLogging {

    override def logSource = s"org.midonet.devices.chain.chain-$id"

    private val chainState = new ChainState

    // The stream of rules that belong to this chain
    private val ruleStream = PublishSubject.create[Observable[Rule]]()
    private val rules = new mutable.HashMap[UUID, RuleState]()

    // The stream of chains pointed to by jump rules of this chain
    private val chainStream = PublishSubject.create[Observable[SimChain]]()
    private val jumpChains = new mutable.HashMap[UUID, JumpChainState]()

    private def unsubscribeFromJmpChain(jumpChainId: UUID): Unit = {
        jumpChains(jumpChainId).complete()
        jumpChains.remove(jumpChainId)
        chainState.jumpTargets.remove(jumpChainId)
    }

    private def mapChain(chain: Chain): SimChain = {
        assertThread()
        log.debug("Received update: {} for chain: {}", chain, id)

        // Subscribe to all rules we are not subscribed to yet.
        chain.getRuleIdsList.filterNot(chainState.rules.contains(_))
            .foreach(ruleId => {
            log.debug("Subscribing to rule: {} for chain: {}", ruleId,
                      id)
            rules(ruleId) = new RuleState(ruleId, vt)
            ruleStream.onNext(rules(ruleId).observable)
        })

        // Unsubscribe from rules that are not part of the chain anymore.
        chainState.chainProto.getRuleIdsList.diff(chain.getRuleIdsList)
            .foreach(ruleId => {
            log.debug("Unsubscribing to rule: {} for chain: {}", ruleId,
                      id)
            rules(ruleId).complete()
            rules.remove(ruleId)

            // If it is a jump rule, unsubscribe from the chain the rule
            // points to.
            if (chainState.rules(ruleId).isInstanceOf[JumpRule]) {
                val jumpChainId = chainState.rules(ruleId)
                    .asInstanceOf[JumpRule].jumpToChainID
                log.debug("Since rule: {} is a jump rule, also " +
                          "unsubscribe to chain: {} the rule points to",
                          ruleId, jumpChainId)
                unsubscribeFromJmpChain(jumpChainId)
            }
            chainState.rules.remove(ruleId)
        })
        chainState.chainProto = chain
        new SimChain(id, chainState.rules.values.toList,
                     chainState.jumpTargets,
                     chainState.chainProto.getName)
    }

    private def mapRule(rule: Rule): SimChain = {
        assertThread()
        val ruleId = rule.getId
        log.debug("Received update: {} for rule: {}", rule, ruleId)

        // This conversion performs validation of the proto.
        // In particular, it checks that if it's a jump rule, the rule's
        // jumpToChainId is non-null.
        val simRule = ZoomConvert.fromProto(rule, classOf[SimRule])

        if (simRule.isInstanceOf[JumpRule]) {
            val jumpChainId = simRule.asInstanceOf[JumpRule]
                .jumpToChainID

            // If this rule points to a new chain, unsubscribe from the
            // previous one.
            if (chainState.rules.contains(ruleId)) {
                val prevJmpRuleId = chainState.rules(ruleId)
                    .asInstanceOf[JumpRule].jumpToChainID

                if (prevJmpRuleId != jumpChainId) {
                    log.debug("Rule: {} is a jump rule and now points " +
                              " to chain: {}, unsubscribing from " +
                              "previous chain: {}", ruleId, jumpChainId,
                              prevJmpRuleId)
                    unsubscribeFromJmpChain(prevJmpRuleId)
                }
            }

            // Subscribe to the chain this jump rule points to.
            jumpChains(jumpChainId) = new JumpChainState(jumpChainId)
            chainStream.onNext(jumpChains(jumpChainId).observable)
        }
        chainState.rules(ruleId) = simRule
        new SimChain(id, chainState.rules.values.toList,
                     chainState.jumpTargets,
                     chainState.chainProto.getName)
    }

    private def mapJumpChain(simChain: SimChain): SimChain = {
        assertThread()
        log.debug("Received update: {} for jump chain: {}", simChain, simChain.id)

        chainState.jumpTargets(simChain.id) = simChain
        new SimChain(id, chainState.rules.values.toList,
                     chainState.jumpTargets,
                     chainState.chainProto.getName)
    }

     private def chainReady(simChain: SimChain): Boolean = {
        assertThread()

        // Since a simChain contains a list of rules, we do not rely on the chain itself to
        // perform this check but rather on the chainState.rules and jumpChains hash maps.
        // This is done for performance reasons.
        rules.keys.forall(chainState.rules.contains(_)) &&
        jumpChains.keys.forall(chainState.jumpTargets.contains(_))
    }

    private def onChainCompleted = makeAction0({
        assertThread()
        log.debug("Chain: {} got deleted", id)

        ruleStream.onCompleted()
        rules.values.foreach(_.complete())
        rules.clear()
        chainStream.onCompleted()
        jumpChains.values.foreach(_.complete())
        jumpChains.clear()
    })

    // The output device observable for the chain mapper:
    //
    //                   on VT scheduler
    //                +-------------------------+  +-----------------+
    // store[Chain]-> |    onChainCompleted     |->|  map(mapChain)  |
    //                +-------------------------+  +-----------------+
    //   onNext(VT.observable[Rule])                      |
    //   +------------------------------------------------+
    //   |             +--------------+  onNext(VT.observable[Chain])
    // Obs[Obs[Rule]]->| map(mapRule) |--------------------------------+
    //                 +--------------+ (only if this is a jump rule)  |
    //   +------------------------------------------------------------------+
    //   |                +-----------------+  +--------------------+
    //   Obs[Obs[Chain]]->|map(mapJumpChain)|->| filter(chainReady) |-> SimChain
    //                    +-----------------+  +--------------------+
    private lazy val deviceObservable =
        // WARNING! The device observable merges the rules, and the chain
        // observables. Publish subjects for rule and chain observable must be added
        // to the merge before observables that may trigger their update, such as
        // the chain observable, which ensures they are subscribed to before
        // emitting any updates.
        Observable.merge[SimChain](Observable.merge(chainStream)
                                       .map[SimChain](makeFunc1(mapJumpChain)),
                                   Observable.merge(ruleStream)
                                       .map[SimChain](makeFunc1(mapRule)),
                                   vt.store.observable(classOf[Chain], id)
                                       .observeOn(vt.scheduler)
                                       .map[SimChain](makeFunc1(mapChain))
                                       .doOnCompleted(onChainCompleted))
            .filter(makeFunc1(chainReady))

    protected override def observable: Observable[SimChain] = deviceObservable
}
