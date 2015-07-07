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
import org.midonet.midolman.topology.ChainMapper.{IpAddressGroupState, RuleState}
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

        private val mark = PublishSubject.create[SimRule]()
        private var _previousRule: SimRule = null
        private var _currentRule: SimRule = null

        /** The observable emitting Rule updates. */
        val observable = vt.store.observable(classOf[TopologyRule],ruleId)
            .observeOn(vt.vtScheduler)
            .takeUntil(mark)
            .map[SimRule](makeFunc1(ruleUpdated))

        /** Returns the previously obtained rule. */
        def previousRule = _previousRule
        /** Returns the last obtained rule. */
        def currentRule = _currentRule
        /**
         * Completes the rule observable. This is called when the chain does
         * not reference this rule anymore.
         */
        def complete() = mark.onCompleted()
        /**
         * Returns true iff the rule is ready to be consumed.
         * When a rule is removed from a chain, its chain id field is cleared.
         * The 2nd part of the conjunction below discards such updates to the
         * rule.
         */
        def isReady = (_currentRule ne null) && (_currentRule.chainId ne null)

        private def ruleUpdated(rule: TopologyRule): SimRule = {
            _previousRule = _currentRule
            _currentRule = ZoomConvert.fromProto(rule, classOf[SimRule])
            _currentRule
        }
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
    private final class IpAddressGroupState(ipAddrGroupId: UUID,
                                            vt: VirtualTopology) {
        /** The number of rules that reference this IP address group. */
        var refCount = 1

        private var currentIpAddrGroup: SimIPAddrGroup = null
        private val mark = PublishSubject.create[SimIPAddrGroup]()

        /** The observable emitting IP address group updates. */
        val observable = VirtualTopology
            .observable[SimIPAddrGroup](ipAddrGroupId)
            .doOnNext(makeAction1(currentIpAddrGroup = _))
            .takeUntil(mark)

        /**
         * Completes the IP address group observable. It is called whenever
         * no rules reference this IP address group anymore.
         */
        def complete() = mark.onCompleted()
        /** The last IP address group obtained. */
        def ipAddressGroup = currentIpAddrGroup
        /** Returns true iff the IP address group is ready to be consumed. */
        def isReady = currentIpAddrGroup ne null
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

    // The chains pointed to by jump rules of this chain: a map between the jump
    // chain identifier and number of rules referencing the chain
    private val jumpChains = new mutable.HashMap[UUID, Int]()

    // The stream of IPAddrGroups referenced by the rules of this chain
    private val ipAddrGroupStream = PublishSubject
        .create[Observable[SimIPAddrGroup]]()
    private val ipAddrGroups = new mutable.HashMap[UUID, IpAddressGroupState]()

    private def subscribeToJumpChain(jumpChainId: UUID): Unit = {
        jumpChains get jumpChainId match {
            case Some(count) =>
                log.debug("Jump chain {} reference count incremented: {}",
                          jumpChainId, Int.box(count + 1))
                jumpChains(jumpChainId) = count + 1
            case None =>
                log.debug("Subscribing to jump chain: {}", jumpChainId)
                jumpChains += jumpChainId -> 1
                requestChains(jumpChains.keySet.toSet)
        }
    }

    private def unsubscribeFromJumpChain(jumpChainId: UUID): Unit = {
        jumpChains get jumpChainId match {
            case Some(1) =>
                log.debug("Unsubscribing from chain {}", jumpChainId)
                jumpChains -= jumpChainId
                requestChains(jumpChains.keySet.toSet)
            case Some(count) =>
                log.debug("Jump chain {} reference count decremented: {}",
                          jumpChainId, Int.box(count - 1))
                jumpChains(jumpChainId) = count - 1
            case None =>
                log.warn("Jump chain {} does not exist", jumpChainId)
        }
    }

    private def subscribeToIpAddrGroup(ipAddrGroupId: UUID): Unit = {
        ipAddrGroups get ipAddrGroupId match {
            case Some(ipAddrGroup) => ipAddrGroup.refCount += 1
            case None =>
                log.debug("Subscribing to IP address group: {}", ipAddrGroupId)
                val ipAddrGroupState = new IpAddressGroupState(ipAddrGroupId, vt)
                ipAddrGroups += ipAddrGroupId -> ipAddrGroupState
                ipAddrGroupStream onNext ipAddrGroupState.observable
        }
    }

    private def unsubscribeFromIpAddrGroup(ipAddrGroupId: UUID): Unit = {
        ipAddrGroups get ipAddrGroupId match {
            case Some(ipAddrGroup) if ipAddrGroup.refCount == 1 =>
                log.debug("Unsubscribing from IP address group: {}",
                          ipAddrGroupId)
                ipAddrGroups(ipAddrGroupId).complete()
                ipAddrGroups.remove(ipAddrGroupId)
            case Some(ipAddrGroup) => ipAddrGroup.refCount -= 1
            case None =>
                log.warn("IP address group {} does not exist", ipAddrGroupId)
        }
    }

    private def chainUpdated(chain: TopologyChain): TopologyChain = {
        assertThread()
        log.debug("Chain updated")

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
            if (rule.currentRule.isInstanceOf[JumpRule]) {
                val jumpChainId = rule.currentRule.asInstanceOf[JumpRule]
                    .jumpToChainID
                unsubscribeFromJumpChain(jumpChainId)
            }

            // Unsubscribe from IPAddrGroups the rule may be referencing.
            val condition = rule.currentRule.getCondition
            if (condition.ipAddrGroupIdSrc ne null) {
                unsubscribeFromIpAddrGroup(condition.ipAddrGroupIdSrc)
            }
            if (condition.ipAddrGroupIdDst ne null) {
                unsubscribeFromIpAddrGroup(condition.ipAddrGroupIdDst)
            }

            rules.remove(ruleId)
        }
        chainProto = chain
        chainProto
    }

    private def ruleUpdated(rule: SimRule): TopologyChain = {
        assertThread()
        log.debug("Rule updated: {}", rule)

        val ruleState = rules get rule.id match {
            case Some(r) => r
            case None =>
                log.warn("Update for unknown rule {}, ignoring", rule.id)
                return chainProto
        }

        // Handle jump rules
        if (rule.isInstanceOf[JumpRule]) {
            val jumpChainId = rule.asInstanceOf[JumpRule].jumpToChainID

            if (ruleState.previousRule ne null) {
                val prevJumpChainId = ruleState.previousRule
                    .asInstanceOf[JumpRule]
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

        // Handle IP address groups.
        val newCond = rule.getCondition
        if (ruleState.previousRule eq null) {
            handleIpAddrGroupSubscription(prevIpAddrGroupId = null,
                                          newCond.ipAddrGroupIdSrc)
            handleIpAddrGroupSubscription(prevIpAddrGroupId = null,
                                          newCond.ipAddrGroupIdDst)
        } else {
            val prevCond = ruleState.previousRule.getCondition
            handleIpAddrGroupSubscription(prevCond.ipAddrGroupIdSrc,
                                          newCond.ipAddrGroupIdSrc)
            handleIpAddrGroupSubscription(prevCond.ipAddrGroupIdDst,
                                          newCond.ipAddrGroupIdDst)
        }
        chainProto
    }

    private def handleIpAddrGroupSubscription(prevIpAddrGroupId: UUID,
                                              newIpAddrGroupId: UUID)
    : Unit = (prevIpAddrGroupId, newIpAddrGroupId) match {
        case (null, null) => // Do nothing.
        case (null, newId) =>
            subscribeToIpAddrGroup(newId)
        case (prevId, null) =>
            unsubscribeFromIpAddrGroup(prevId)
        case (prevId, newId) if prevId != newId =>
            unsubscribeFromIpAddrGroup(prevId)
            subscribeToIpAddrGroup(newId)
        case _ => // Do nothing.
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
        jumpChains.clear()
        ipAddrGroupStream.onCompleted()
        ipAddrGroups.values.foreach(_.complete())
        ipAddrGroups.clear()
    }

    private def buildChain(update: Any): SimChain = {
        // Set the IP address groups source and destination addresses in the
        // rules.
        for (rule <- rules.values) {
            val cond = rule.currentRule.getCondition
            if (cond.ipAddrGroupIdSrc ne null) {
                cond.ipAddrGroupSrc = ipAddrGroups(cond.ipAddrGroupIdSrc)
                    .ipAddressGroup
            }
            if (cond.ipAddrGroupIdDst ne null) {
                cond.ipAddrGroupDst = ipAddrGroups(cond.ipAddrGroupIdDst)
                    .ipAddressGroup
            }
        }
        val chain = new SimChain(chainId,
                                 ruleIds.map(rules(_).currentRule).asJava,
                                 currentChains, chainProto.getName)
        log.debug("Emitting {}", chain)
        chain
    }

    private lazy val chainObservable =
        vt.store.observable(classOf[TopologyChain], chainId)
            .observeOn(vt.vtScheduler)
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
