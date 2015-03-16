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
     * Stores the state for a rule. As for chains above, if a rule is removed
     * from the chain we are providing an observable for, we unsubscribe from
     * it by calling the complete() method below.
     *
     * @param ruleId The id of the rule we want to start observing.
     * @param vt The virtual topology object.
     */
    private final class RuleState(ruleId: UUID, vt: VirtualTopology) {
        private var previousRule: SimRule = null
        def prevRule = previousRule
        private var currentRule: SimRule = null
        def curRule = currentRule
        private val mark = PublishSubject.create[SimRule]()
        val observable = vt.store.observable(classOf[TopologyRule],ruleId)
            .takeUntil(mark)
            .map[SimRule](makeFunc1(ZoomConvert.fromProto(_, classOf[SimRule])))
            .observeOn(vt.scheduler)
            .doOnNext(makeAction1(newRule => {
                previousRule = currentRule
                currentRule = newRule
            }))
        // When a rule is removed from a chain, its chain id field is cleared.
        // The 2nd part of the conjunction below discards such updates to the
        // rule.
        def isReady = (currentRule ne null) && (currentRule.chainId ne null)
        def complete() = mark.onCompleted()
    }

    private final class IPAddrGroupState(ipAddrGroupId: UUID,
                                         vt: VirtualTopology) {
        private val mark = PublishSubject.create[SimIPAddrGroup]()
        val observable = VirtualTopology.observable[SimIPAddrGroup](ipAddrGroupId)
            .doOnNext(makeAction1(currentIPAddrGroup = _))
            .takeUntil(mark)
        private var currentIPAddrGroup: SimIPAddrGroup = null
        def ipAddrGroup = currentIPAddrGroup
        def isReady = currentIPAddrGroup ne null
        def complete() = mark.onCompleted()
        var refCount = 1
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
            updateChains(jumpChainRefCount.keySet.toSet)
        } else {
            jumpChainRefCount(jumpChainId) += 1
        }
    }

    private def unsubscribeFromJumpChain(jumpChainId: UUID): Unit = {
        jumpChainRefCount(jumpChainId) -= 1

        if (jumpChainRefCount(jumpChainId) == 0) {
            log.debug("Unsubscribing from chain {}", jumpChainId)
            jumpChainRefCount.remove(jumpChainId)
            updateChains(jumpChainRefCount.keySet.toSet)
        }
    }

    private def subscribeToIPAddrGroup(ipAddrGroupId: UUID): Unit = {
        if (!ipAddrGroups.contains(ipAddrGroupId)) {
            log.debug("Subscribing to IPAddrGroup: {}", ipAddrGroupId)

            ipAddrGroups(ipAddrGroupId) =
                new IPAddrGroupState(ipAddrGroupId, vt)
            ipAddrGroupStream onNext ipAddrGroups(ipAddrGroupId).observable
        } else {
            ipAddrGroups(ipAddrGroupId).refCount += 1
        }
    }

    private def unsubscribeFromIPAddrGroup(ipAddrGroupId: UUID): Unit = {
        ipAddrGroups(ipAddrGroupId).refCount -= 1

        if (ipAddrGroups(ipAddrGroupId).refCount == 0) {
            log.debug("Unsubscribing from IPAddrGroup: {}", ipAddrGroupId)
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
            rules(ruleId) = new RuleState(ruleId, vt)
            ruleStream.onNext(rules(ruleId).observable)
        }

        // Unsubscribe from rules that are not part of the chain anymore.
        for ((ruleId, rule) <- rules.toList if !ruleIds.contains(ruleId)) {
            log.debug("Unsubscribing from rule: {}", ruleId)
            rule.complete()

            // If it is a jump rule, unsubscribe from the chain the rule
            // references.
            if (rules(ruleId).curRule.isInstanceOf[JumpRule]) {
                val jumpChainId = rules(ruleId).curRule
                    .asInstanceOf[JumpRule].jumpToChainID
                unsubscribeFromJumpChain(jumpChainId)
            }

            // Unsubscribe from IPAddrGroups the rule may be referencing.
            val cond = rules(ruleId).curRule.getCondition
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
        val ruleId = rule.id
        log.debug("Received updated rule: {}", rule)

        // Handle jump rules
        if (rule.isInstanceOf[JumpRule]) {
            val jumpChainId = rule.asInstanceOf[JumpRule].jumpToChainID

            if (rules(ruleId).prevRule ne null) {
                val prevJumpChainId = rules(ruleId).prevRule
                    .asInstanceOf[JumpRule].jumpToChainID

                // If this rule points to a new chain, unsubscribe from the
                // previous one.
                if (prevJumpChainId != jumpChainId) {
                    log.debug("Rule: {} is a jump rule and now references " +
                              "chain: {}, decreasing ref. count of " +
                              "previous chain: {}", ruleId, jumpChainId,
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
        if (rules(rule.id).prevRule eq null) {
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
        log.debug("Chain was deleted")

        ruleStream.onCompleted()
        rules.values.foreach(_.complete())
        rules.clear()
        super.deviceDeleted()
        jumpChainRefCount.clear()
        ipAddrGroupStream.onCompleted()
        ipAddrGroups.values.foreach(_.complete())
        ipAddrGroups.clear()
    }

    private def buildChain(update: Any): SimChain = {
        // Set IPAddrGroup source and destination addresses in the rules.
        for (rule <- rules.values.map(_.curRule)) {
            val cond = rule.getCondition
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
            .doOnCompleted(makeAction0(chainDeleted))

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
