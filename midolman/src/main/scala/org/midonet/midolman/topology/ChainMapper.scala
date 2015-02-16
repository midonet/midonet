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
import scala.collection.JavaConverters._

import rx.Observable
import rx.subjects.PublishSubject

import org.midonet.cluster.data.ZoomConvert
import org.midonet.cluster.models.Topology.{Rule => TopologyRule, Chain => TopologyChain}
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.midolman.logging.MidolmanLogging
import org.midonet.midolman.rules.{Rule => SimRule, JumpRule}
import org.midonet.midolman.simulation.{Chain => SimChain}
import org.midonet.midolman.topology.ChainMapper.{JumpChainState, RuleState}
import org.midonet.util.functors.{makeAction0, makeFunc1}

object ChainMapper {

    /**
     * Stores the state for a chain one of the rules of this chain points to.
     * If the rule starts pointing to another chain or if the rule is deleted,
     * we unsubscribe from the chain by calling the updateChains of our
     * super class.
     *
     * Since two (or more) jump rules can point to the same chain, we keep
     * the number of references to every chain. In doing so, we are able to
     * determine when we can safely unsubscribe from a chain.
     *
     * @param chainId The id of the chain one of the rules points to.
     */
    private final class JumpChainState(chainId: UUID) {
        private var currentChain: SimChain = null
        def chain = currentChain
        def setChain(newChain: SimChain) = { currentChain = newChain }
        var refCount = 1
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

        private val mark = PublishSubject.create[TopologyRule]()
        val observable =
            vt.store.observable[TopologyRule](classOf[TopologyRule],ruleId)
                .takeUntil(mark)
                .observeOn(vt.scheduler)
        private var currentRule: SimRule = null
        def rule = currentRule
        def setRule(newRule: SimRule) = { currentRule = newRule }
        // When a rule is removed from a chain, its chain id field is cleared.
        // The 2nd part of the conjunction below discards such updates to the
        // rule.
        def isReady = (currentRule ne null) && (currentRule.chainId ne null)
        def complete() = mark.onCompleted()
    }

}

final class ChainMapper(chainId: UUID, vt: VirtualTopology)
   extends DeviceWithChainsMapper[SimChain](chainId, vt)
   with MidolmanLogging {

    override def logSource = s"org.midonet.devices.chain.chain-$chainId"

    private var chainProto: TopologyChain = TopologyChain.newBuilder.build()

    // The stream of rules that belong to this chain
    private val ruleStream = PublishSubject.create[Observable[TopologyRule]]()
    private val rules = new mutable.HashMap[UUID, RuleState]()

    // The chains pointed to by jump rules of this chain.
    private val jumpChains = new mutable.HashMap[UUID, JumpChainState]()

    private def subscribeToJumpChain(jumpChainId: UUID): Unit = {
        if (!jumpChains.contains(jumpChainId)) {
            log.debug("Subscribing to jump chain: {}", jumpChainId)
            jumpChains(jumpChainId) = new JumpChainState(jumpChainId)
            updateChains(jumpChains.keySet.toSet)
        } else {
            jumpChains(jumpChainId).refCount += 1
        }
    }

    private def unsubscribeFromJumpChain(jumpChainId: UUID): Unit = {
        jumpChains(jumpChainId).refCount -= 1

        if (jumpChains(jumpChainId).refCount == 0) {
            log.debug("Unsubscribing from chain {} referenced by jump rule {}",
                      jumpChainId, jumpChainId)
            jumpChains.remove(jumpChainId)
            updateChains(jumpChains.keySet.toSet)
        }
    }

    private def chainUpdated(chain: TopologyChain): TopologyChain = {
        assertThread()
        log.debug("Received update: {} for chain: {}", chain, chainId)

        // Subscribe to all rules we are not subscribed to yet.
        val ruleIds = chain.getRuleIdsList.asScala.map(_.asJava)
        for (ruleId <- ruleIds if !rules.contains(ruleId)) {
            log.debug("Subscribing to rule: {}", ruleId)
            rules(ruleId) = new RuleState(ruleId, vt)
            ruleStream.onNext(rules(ruleId).observable)
        }

        // Unsubscribe from rules that are not part of the chain anymore.
        for ((ruleId, rule) <- rules.toList if !ruleIds.contains(ruleId)) {
            log.debug("Unsubscribing from rule: {}", ruleId)
            rule.complete()

            // If it is a jump rule, unsubscribe from the chain the rule
            // points to.
            if (rules(ruleId).rule.isInstanceOf[JumpRule]) {
                val jumpChainId = rules(ruleId).rule
                    .asInstanceOf[JumpRule].jumpToChainID
                unsubscribeFromJumpChain(jumpChainId)
            }
            rules.remove(ruleId)
        }
        chainProto = chain
        chainProto
    }

    private def ruleUpdated(rule: TopologyRule): TopologyChain = {
        assertThread()
        val ruleId = rule.getId.asJava
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
            if (rules(ruleId).isReady) {
                val prevJumpChainId = rules(ruleId).rule
                    .asInstanceOf[JumpRule].jumpToChainID

                if (prevJumpChainId != jumpChainId) {
                    log.debug("Rule: {} is a jump rule and now references " +
                              " chain: {}, decreasing ref. count of " +
                              "previous chain: {}", ruleId, jumpChainId,
                              prevJumpChainId)
                    unsubscribeFromJumpChain(prevJumpChainId)
                    subscribeToJumpChain(jumpChainId)
                }
            } else {
                subscribeToJumpChain(jumpChainId)
            }
        }
        rules(ruleId).setRule(simRule)
        chainProto
    }

    private def jumpChainUpdated(simChain: SimChain): TopologyChain = {
        assertThread()
        log.debug("Received update: {} for jump chain: {}", simChain,
                  simChain.id)

        jumpChains(simChain.id).setChain(simChain)
        chainProto
    }

     private def chainReady(chain: TopologyChain): Boolean = {
         assertThread()
         val ready = rules.forall(_._2.isReady) && areChainsReady
         log.debug("Chain ready: {}", Boolean.box(ready))
         ready
    }

    private def chainDeleted = {
        assertThread()
        log.debug("Chain was deleted")

        ruleStream.onCompleted()
        rules.values.foreach(_.complete())
        rules.clear()
        super.deviceDeleted()
        jumpChains.clear()
    }

    private def buildChain(chain: TopologyChain): SimChain = {
        val chain =
            new SimChain(chainId, rules.values.map(_.rule).toList.asJava,
                         jumpChains.map(jumpChainState =>
                             (jumpChainState._1, jumpChainState._2.chain)
                         ), chainProto.getName)
        log.debug("New chain {}", chain.asTree(2 /* indent */))
        chain
    }

    // The output device observable for the chain mapper:
    //
    //                   on VT scheduler
    //                +-----------------------+  +---------------------+
    // store[Chain]-> |    onChainDeleted     |->|  map(chainUpdated)  |
    //                +-----------------------+  +---------------------+
    //   onNext(VT.observable[Rule])                      |
    //   +------------------------------------------------+
    //   |             +------------------+  onNext(VT.observable[Chain])
    // Obs[Obs[Rule]]->| map(ruleUpdated) |--------------------------------+
    //                 +------------------+ (only if this is a jump rule)  |
    //   +-----------------------------------------------------------------+
    //   |                +---------------------+   +---------------------+
    //   Obs[Obs[Chain]]->|map(jumpChainUpdated)|-->| filter(chainReady)  |---+
    //                    +---------------------+   +---------------------+   |
    //   +--------------------------------------------------------------------+
    //   |    +-----------------+
    //   +--->| map(buildChain) | --> simChain
    //        +-----------------+
    protected override lazy val observable =
        // WARNING! The device observable merges the rules, and the chain
        // observables. Publish subjects for rule and chain observable must be added
        // to the merge before observables that may trigger their update, such as
        // the chain observable, which ensures they are subscribed to before
        // emitting any updates.
        Observable.merge[TopologyChain](chainsObservable
                                            .map[TopologyChain](makeFunc1(jumpChainUpdated)),
                                        Observable.merge(ruleStream)
                                            .map[TopologyChain](makeFunc1(ruleUpdated)),
                                        vt.store.observable(classOf[TopologyChain], chainId)
                                            .observeOn(vt.scheduler)
                                            .map[TopologyChain](makeFunc1(chainUpdated))
                                            .doOnCompleted(makeAction0(chainDeleted)))
            .filter(makeFunc1(chainReady))
            .map[SimChain](makeFunc1(buildChain))
}
