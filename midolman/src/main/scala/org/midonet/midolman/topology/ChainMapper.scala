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
        var simChain: SimChain = null
        def isReady() = simChain ne null
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
        private val mark = PublishSubject.create[TopologyRule]()
        val observable = vt.store.observable[TopologyRule](classOf[TopologyRule],
                                                           ruleId)
            .takeUntil(mark)
            .observeOn(vt.scheduler)
        var simRule: SimRule = null
        def isReady() = simRule ne null
        def complete() = mark.onCompleted()
    }
}

// TODO(nicolas): Can there be two jump rules pointing to the same chain?
// If that's the case, then we need to fix the code to check if there
// is another jump rule pointing to the same chain before unsubscribing
// from that chain.
sealed class ChainMapper(id: UUID, vt: VirtualTopology)
   extends VirtualDeviceMapper[SimChain](id, vt)
   with MidolmanLogging {

    override def logSource = s"org.midonet.devices.chain.chain-$id"

    private var chainProto: TopologyChain = TopologyChain.newBuilder.build()

    // The stream of rules that belong to this chain
    private val ruleStream = PublishSubject.create[Observable[TopologyRule]]()
    private val rules = new mutable.HashMap[UUID, RuleState]()

    // The stream of chains pointed to by jump rules of this chain
    private val chainStream = PublishSubject.create[Observable[SimChain]]()
    private val jumpChains = new mutable.HashMap[UUID, JumpChainState]()

    private def subscribeToJumpChain(jumpChainId: UUID): Unit = {
        log.debug("Subscribing to jump chain: {}", jumpChainId)
        jumpChains(jumpChainId) = new JumpChainState(jumpChainId)
        chainStream.onNext(jumpChains(jumpChainId).observable)
    }

    private def unsubscribeFromJumpChain(jumpChainId: UUID): Unit = {
        jumpChains(jumpChainId).complete()
        jumpChains.remove(jumpChainId)
    }

    private def mapChain(chain: TopologyChain): TopologyChain = {
        assertThread()
        log.debug("Received update: {} for chain: {}", chain, id)

        // Subscribe to all rules we are not subscribed to yet.
        chain.getRuleIdsList.asScala.map(_.asJava)
            .filterNot(rules.keySet.contains(_)).foreach(ruleId => {

            log.debug("Subscribing to rule: {} for chain: {}", ruleId, id)
            rules(ruleId) = new RuleState(ruleId, vt)
            ruleStream.onNext(rules(ruleId).observable)
        })

        // Unsubscribe from rules that are not part of the chain anymore.
        chainProto.getRuleIdsList.asScala.diff(chain.getRuleIdsList.asScala)
            .foreach(ruleId => {

            val pojoId = ruleId.asJava
            log.debug("Unsubscribing from rule: {} for chain: {}", pojoId, id)
            rules(pojoId).complete()

            // If it is a jump rule, unsubscribe from the chain the rule
            // points to.
            if (rules(pojoId).simRule.isInstanceOf[JumpRule]) {
                val jumpChainId = rules(pojoId).simRule
                    .asInstanceOf[JumpRule].jumpToChainID
                log.debug("Unsubscribing from chain {} pointed to by jump rule {}",
                          jumpChainId, pojoId)
                unsubscribeFromJumpChain(jumpChainId)
            }
            rules.remove(pojoId)
        })
        chainProto = chain
        chainProto
    }

    private def mapRule(rule: TopologyRule): TopologyChain = {
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
            if (rules(ruleId).isReady()) {
                val prevJumpChainId = rules(ruleId).simRule
                    .asInstanceOf[JumpRule].jumpToChainID

                if (prevJumpChainId != jumpChainId) {
                    log.debug("Rule: {} is a jump rule and now points " +
                              " to chain: {}, unsubscribing from " +
                              "previous chain: {}", ruleId, jumpChainId,
                              prevJumpChainId)
                    unsubscribeFromJumpChain(prevJumpChainId)
                    subscribeToJumpChain(jumpChainId)
                }
            } else {
                subscribeToJumpChain(jumpChainId)
            }
        }
        rules(ruleId).simRule = simRule
        chainProto
    }

    private def mapJumpChain(simChain: SimChain): TopologyChain = {
        assertThread()
        log.debug("Received update: {} for jump chain: {}", simChain,
                  simChain.id)

        jumpChains(simChain.id).simChain = simChain
        chainProto
    }

     private def chainReady(chain: TopologyChain): Boolean = {
        assertThread()
        val ready = rules.count(!_._2.isReady) == 0 &&
                    jumpChains.count(!_._2.isReady()) == 0
         log.debug("**** chain ready: " + ready)
         ready
    }

    private def onChainDeleted = makeAction0({
        assertThread()
        log.debug("Chain: {} was deleted", id)

        ruleStream.onCompleted()
        rules.values.foreach(_.complete())
        rules.clear()
        chainStream.onCompleted()
        jumpChains.values.foreach(_.complete())
        jumpChains.clear()
    })

    private def buildChain(chain: TopologyChain): SimChain =
        new SimChain(id, rules.values.map(_.simRule).toList.asJava,
                     jumpChains.map(jumpChainState =>
                         (jumpChainState._1, jumpChainState._2.simChain)
                     ), chainProto.getName)

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
    protected override lazy val observable =
        // WARNING! The device observable merges the rules, and the chain
        // observables. Publish subjects for rule and chain observable must be added
        // to the merge before observables that may trigger their update, such as
        // the chain observable, which ensures they are subscribed to before
        // emitting any updates.
        Observable.merge[TopologyChain](Observable.merge(chainStream)
                                            .map[TopologyChain](makeFunc1(mapJumpChain)),
                                        Observable.merge(ruleStream)
                                            .map[TopologyChain](makeFunc1(mapRule)),
                                        vt.store.observable(classOf[TopologyChain], id)
                                            .observeOn(vt.scheduler)
                                            .map[TopologyChain](makeFunc1(mapChain))
                                            .doOnCompleted(onChainDeleted))
            .filter(makeFunc1(chainReady))
            .map[SimChain](makeFunc1(buildChain))
}
