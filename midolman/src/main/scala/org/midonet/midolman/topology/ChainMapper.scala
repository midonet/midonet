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
import rx.subjects.PublishSubject

import org.midonet.cluster.data.ZoomConvert
import org.midonet.cluster.models.Topology.{Chain => TopologyChain, Rule => TopologyRule}
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.midolman.logging.MidolmanLogging
import org.midonet.midolman.rules.{JumpRule, Rule => SimRule}
import org.midonet.midolman.simulation.{Chain => SimChain, IPAddrGroup => SimIPAddrGroup}
import org.midonet.midolman.topology.ChainMapper.{IPAddrGroupState, RuleState}
import org.midonet.util.functors.{makeAction0, makeAction1, makeFunc1}

object ChainMapper {
    /**
     * Stores the state for a rule and exposes an observable for it. If a rule
     * is removed from the chain we are providing an observable for, we
     * unsubscribe from it by calling the complete() method below.
     *
     * @param ruleId The id of the rule we want to start observing.
     * @param vt The virtual topology object.
     */
    private final class RuleState(ruleId: UUID, vt: VirtualTopology) {
        private def ruleUpdated(rule: TopologyRule): SimRule = {
            previousRule = currentRule
            currentRule = ZoomConvert.fromProto(rule, classOf[SimRule])
            currentRule
        }

        private var previousRule: SimRule = null
        /** Returns the previously obtained rule. */
        def prevRule = previousRule
        private var currentRule: SimRule = null
        /** Returns the last obtained rule. */
        def curRule = currentRule
        /**
         * Returns true iff the rule is ready to be consumed.
         * When a rule is removed from a chain, its chain id field is cleared.
         * The 2nd part of the conjunction below discards such updates to the
         * rule.
         */
        def isReady = (currentRule ne null) && (currentRule.chainId ne null)
        private val mark = PublishSubject.create[SimRule]()
        /** The observable emitting Rule updates. */
        val observable = vt.store.observable(classOf[TopologyRule],ruleId)
            .observeOn(vt.scheduler)
            .takeUntil(mark)
            .map[SimRule](makeFunc1(ruleUpdated))

        /**
         * Completes the rule observable. This is called when the chain does
         * not reference this rule anymore.
         */
        def complete() = mark.onCompleted()
    }

    /**
     * Stores the state for an IP address group and exposes an observable
     * for it. If no rules reference this IP address group anymore, we
     * unsubscribe from it by calling the complete() method below.
     *
     * @param ipAddrGroupId The id of the IP address group we want to
     *                      start observing.
     * @param vt The virtual topology object.
     */
    private final class IPAddrGroupState(ipAddrGroupId: UUID,
                                         vt: VirtualTopology) {
        /** The number of rules that reference this IP address group. */
        var refCount = 1
        private var currentIPAddrGroup: SimIPAddrGroup = null
        /** The last IP address group obtained. */
        def ipAddrGroup = currentIPAddrGroup
        /** Returns true iff the IP address group is ready to be consumed. */
        def isReady = currentIPAddrGroup ne null
        private val mark = PublishSubject.create[SimIPAddrGroup]()
        /** The observable emitting IP address group updates. */
        val observable = VirtualTopology.observable[SimIPAddrGroup](ipAddrGroupId)
            .doOnNext(makeAction1(currentIPAddrGroup = _))
            .takeUntil(mark)
        /**
         * Completes the IP address group observable. It is called whenever
         * no rules reference this IP address group anymore.
         */
        def complete() = mark.onCompleted()
    }
}

final class ChainMapper(chainId: UUID, vt: VirtualTopology)
    extends DeviceWithChainsMapper[SimChain](chainId, vt)
    with MidolmanLogging {

    override def logSource = s"org.midonet.devices.chain.chain-$chainId"

    private var chainProto: TopologyChain = TopologyChain.newBuilder.build()

    // The stream of rules that belong to this chain
    private val ruleStream = PublishSubject.create[Observable[SimRule]]()
    private val rules = new mutable.HashMap[UUID, RuleState]()
    // The ordered list of rules in the chain
    private var ruleIds: Seq[UUID] = mutable.Seq.empty

    // The chains pointed to by jump rules of this chain.
    private val jumpChainRefCount = new mutable.HashMap[UUID, Int]()

    // The stream of IPAddrGroups referenced by the rules of this chain
    private val ipAddrGroupStream = PublishSubject
        .create[Observable[SimIPAddrGroup]]()
    private val ipAddrGroups = new mutable.HashMap[UUID, IPAddrGroupState]()

    private def subscribeToJumpChain(jumpChainId: UUID): Unit = {
        if (!jumpChainRefCount.contains(jumpChainId)) {
            log.debug("Subscribing to jump chain: {}", jumpChainId)
            jumpChainRefCount(jumpChainId) = 1
            requestChains(jumpChainRefCount.keySet.toSet)
        } else {
            jumpChainRefCount(jumpChainId) += 1
        }
    }

    private def unsubscribeFromJumpChain(jumpChainId: UUID): Unit = {
        jumpChainRefCount(jumpChainId) -= 1

        if (jumpChainRefCount(jumpChainId) == 0) {
            log.debug("Unsubscribing from chain {}", jumpChainId)
            jumpChainRefCount.remove(jumpChainId)
            requestChains(jumpChainRefCount.keySet.toSet)
        }
    }

    private def subscribeToIPAddrGroup(ipAddrGroupId: UUID): Unit = {
        ipAddrGroups get ipAddrGroupId match {
            case Some(ipAddrGroup) => ipAddrGroup.refCount += 1
            case None =>
                log.debug("Subscribing to IP address group: {}", ipAddrGroupId)
                val ipAddrGroupState = new IPAddrGroupState(ipAddrGroupId, vt)
                ipAddrGroups += ipAddrGroupId -> ipAddrGroupState
                ipAddrGroupStream onNext ipAddrGroupState.observable
        }
    }

    private def unsubscribeFromIPAddrGroup(ipAddrGroupId: UUID): Unit = {
        val ipAddrGroup = ipAddrGroups(ipAddrGroupId)
        ipAddrGroup.refCount -= 1

        if (ipAddrGroup.refCount == 0) {
            log.debug("Unsubscribing from IP address group: {}", ipAddrGroupId)
            ipAddrGroups(ipAddrGroupId).complete()
            ipAddrGroups.remove(ipAddrGroupId)
        }
    }

    private def chainUpdated(chain: TopologyChain): TopologyChain = {
        assertThread()
        log.debug("Received update for chain: {}", chainId)

        // Store the rule order in the chain.
        ruleIds = chain.getRuleIdsList.asScala.map(_.asJava)

        // Subscribe to all rules we are not subscribed to yet.
        for (ruleId <- ruleIds if !rules.contains(ruleId)) {
            log.debug("Subscribing to rule: {}", ruleId)
            val ruleState = new RuleState(ruleId, vt)
            rules(ruleId) = ruleState
            ruleStream.onNext(ruleState.observable)
        }

        // Unsubscribe from rules that are not part of the chain anymore.
        for ((ruleId, rule) <- rules.toList if !ruleIds.contains(ruleId)) {
            log.debug("Unsubscribing from rule: {}", ruleId)
            rule.complete()

            // If it is a jump rule, unsubscribe from the chain the rule
            // references.
            if (rule.curRule.isInstanceOf[JumpRule]) {
                val jumpChainId = rule.curRule.asInstanceOf[JumpRule]
                    .jumpToChainID
                unsubscribeFromJumpChain(jumpChainId)
            }

            // Unsubscribe from IPAddrGroups the rule may be referencing.
            val cond = rule.curRule.getCondition
            if (cond.ipAddrGroupIdSrc ne null) {
                unsubscribeFromIPAddrGroup(cond.ipAddrGroupIdSrc)
            }
            if (cond.ipAddrGroupIdDst ne null) {
                unsubscribeFromIPAddrGroup(cond.ipAddrGroupIdDst)
            }

            rules.remove(ruleId)
        }
        chainProto = chain
        chainProto
    }

    private def ruleUpdated(rule: SimRule): TopologyChain = {
        assertThread()
        log.debug("Received updated rule: {}", rule)

        val ruleState = rules get rule.id match {
            case Some(r) => r
            case None =>
                log.warn("Update for unknown rule {}, ignoring", rule.id)
                return chainProto
        }

        // Handle jump rules
        if (rule.isInstanceOf[JumpRule]) {
            val jumpChainId = rule.asInstanceOf[JumpRule].jumpToChainID

            if (ruleState.prevRule ne null) {
                val prevJumpChainId = ruleState.prevRule.asInstanceOf[JumpRule]
                    .jumpToChainID

                // If this rule points to a new chain, unsubscribe from the
                // previous one.
                if (prevJumpChainId != jumpChainId) {
                    log.debug("Rule: {} is a jump rule and now references " +
                              "chain: {}, decreasing ref. count of " +
                              "previous chain: {}", rule.id, jumpChainId,
                              prevJumpChainId)
                    unsubscribeFromJumpChain(prevJumpChainId)
                    subscribeToJumpChain(jumpChainId)
                }
            } else {
                subscribeToJumpChain(jumpChainId)
            }
        }

        // Handle IP address groups. We only subscribe to an IP address group
        // the first time we receive the rule. A rule's IP address groups are
        // assumed to remain unchanged.
        if (ruleState.prevRule eq null) {
            val cond = rule.getCondition
            if (cond.ipAddrGroupIdSrc ne null) {
                subscribeToIPAddrGroup(cond.ipAddrGroupIdSrc)
            }
            if (cond.ipAddrGroupIdDst ne null) {
                subscribeToIPAddrGroup(cond.ipAddrGroupIdDst)
            }
        }

        chainProto
    }

    private def chainReady(update: Any): Boolean = {
        assertThread()
        val ready = rules.forall(_._2.isReady) && areChainsReady &&
                    ipAddrGroups.forall(_._2.isReady)
        log.debug("Chain ready: {}", Boolean.box(ready))
        ready
    }

    private def chainDeleted() = {
        assertThread()
        log.debug("Chain deleted")

        ruleStream.onCompleted()
        rules.values.foreach(_.complete())
        rules.clear()
        completeChains()
        jumpChainRefCount.clear()
        ipAddrGroupStream.onCompleted()
        ipAddrGroups.values.foreach(_.complete())
        ipAddrGroups.clear()
    }

    private def buildChain(update: Any): SimChain = {
        // Set IPAddrGroup source and destination addresses in the rules.
        for (rule <- rules.values) {
            val cond = rule.curRule.getCondition
            if ((cond.ipAddrGroupIdSrc ne null) && (cond.ipAddrGroupSrc eq null)) {
                cond.ipAddrGroupSrc = ipAddrGroups(cond.ipAddrGroupIdSrc)
                    .ipAddrGroup
            }
            if ((cond.ipAddrGroupIdDst ne null) && (cond.ipAddrGroupDst eq null)) {
                cond.ipAddrGroupDst = ipAddrGroups(cond.ipAddrGroupIdDst)
                    .ipAddrGroup
            }
        }
        val chain = new SimChain(chainId, ruleIds.map(rules(_).curRule).asJava,
                                 currentChains, chainProto.getName)
        log.debug("Emitting {}", chain.asTree(2 /* indent */))
        chain
    }

    private lazy val chainObservable =
        vt.store.observable(classOf[TopologyChain], chainId)
            .observeOn(vt.scheduler)
            .map[TopologyChain](makeFunc1(chainUpdated))
            .doOnCompleted(makeAction0(chainDeleted()))

    // The output device observable for the chain mapper:
    //
    //                    on VT scheduler
    //                +-----------------+  +---------------------+
    // store[Chain]-> |  chainDeleted   |->|  map(chainUpdated)  |
    //                +-----------------+  +---------------------+
    //   onNext(VT.observable[Rule])                  |
    //   +--------------------------------------------+
    //   |             +------------------+
    // Obs[Obs[Rule]]->| map(ruleUpdated) |---------+
    //                 +------------------+         |
    // +--------------------------------------------+
    // |  If it's a jump rule
    // |  onNext(VT.observable[chain])
    // +--> chainsObservable ---------------------+
    // |  If the rule references an IPAddrGroup   |   +--------------------+
    // |  onNext(VT.observable[IPAddrGroup])      |-->| filter(chainReady) |-+
    // +--> Obs[Obs[IPAddrGroup]]-----------------+   +--------------------+ |
    //                  +----------------------------------------------------+
    //                  |    +----------------+
    //                  +--> | map(buildChain)|-> simChain
    //                       +----------------+
    protected override lazy val observable =
        // WARNING! The device observable merges the rules, and the chain
        // observables. Publish subjects for rule and chain observable must be
        // added to the merge before observables that may trigger their update,
        // such as the chain observable, which ensures they are subscribed to
        // before emitting any updates.
        Observable.merge[Any](chainsObservable,
                              Observable.merge(ipAddrGroupStream),
                              Observable.merge(ruleStream)
                                  .map[TopologyChain](makeFunc1(ruleUpdated)),
                              chainObservable)
            .filter(makeFunc1(chainReady))
            .map[SimChain](makeFunc1(buildChain))
}
