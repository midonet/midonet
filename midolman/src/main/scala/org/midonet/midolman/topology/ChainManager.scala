/*
 * Copyright (c) 2013 Midokura SARL, All Rights Reserved.
 */
package org.midonet.midolman.topology

import java.util
import java.util.UUID
import scala.collection.JavaConversions._
import scala.collection.mutable

import akka.actor.{ActorRef, Actor}

import org.midonet.cluster.Client
import org.midonet.cluster.client.ChainBuilder
import org.midonet.midolman.FlowController.InvalidateFlowsByTag
import org.midonet.midolman.logging.ActorLogWithoutPath
import org.midonet.midolman.rules.{JumpRule, Rule}
import org.midonet.midolman.simulation.{IPAddrGroup, Chain}
import org.midonet.midolman.topology.ChainManager._
import org.midonet.sdn.flows.FlowTagger

object ChainManager {

    class ChainBuilderImpl(val chainMgr: ActorRef) extends ChainBuilder {
        def setRules(rules: util.List[Rule]) {
            chainMgr ! RulesUpdate(rules)
        }

        def setRules(ruleOrder: util.List[UUID], rules: util.Map[UUID, Rule]) {
            val orderedRules = ruleOrder.map(x => rules.get(x))
            setRules(orderedRules)
        }

        def setName(name: String) {
            chainMgr ! ChainName(name)
        }
    }

    case class RulesUpdate(rules: util.List[Rule])
    case class ChainName(name: String)
}

class ChainManager(val id: UUID, val clusterClient: Client)
        extends Actor with ActorLogWithoutPath {

    import context.system // Used implicitly. Don't delete.
    import ChainManager._
    import VirtualTopologyActor.DeviceRequest
    import VirtualTopologyActor.IPAddrGroupRequest
    import VirtualTopologyActor.Unsubscribe
    import VirtualTopologyActor.ChainRequest

    override def preStart() {
        clusterClient.getChain(id, new ChainBuilderImpl(self))
    }

    private var chainName: Option[String] = None
    // Store the chains that these rules jump to.
    private val idToChain = mutable.Map[UUID, Chain]()
    // Store the IP address groups that rules reference.
    private val idToIPAddrGroup = mutable.Map[UUID, IPAddrGroup]()
    // Store the number of rules that jump to each chain.
    private val idToRefCount = mutable.Map[UUID, Int]()
    // Number of resources (Chain or IPAddrGroup) we're waiting for.
    private var waitingForResources: Int = 0
    // when publishing a new chain, this variables tells if an invalidation msg
    // should be sent along.
    private var publishingNeedsInvalidation: Boolean = true //false

    // an internal flag that tells if the manager needs to wait for the chain
    // name. This flag should only be set to true when an update to Jump targets
    // or rules has been called before the name update.
    private var waitingForName: Boolean = false

    private var rules: util.List[Rule] = Nil

    override val toString = "ChainManager[id=" + id + "]"

    /**
     * Increment the refcount for another Chain on which this Chain
     * depends via a JumpRule, and subscribe to that Chain's updates
     * if not already subscribed.
     */
    private def incrChainRefCount(id: UUID): Unit =
        incrResourceRefCount(id, "Chain", ChainRequest(_, true))

    /**
     * Increments the refcount for an IPAddrGroup on which this Chain
     * depends via a Rule's Condition, and subscribe to that
     * IPAddrGroup's updates if not already subscribed.
     */
    private def incrIPAddrGroupRefCount(id: UUID): Unit =
        incrResourceRefCount(id, "IPAddrGroup", IPAddrGroupRequest(_, true))

    private def incrResourceRefCount(id: UUID,
                                     resourceType: String,
                                     reqFactory: UUID => DeviceRequest): Unit = {
        idToRefCount.get(id) match {
            case Some(refCount) =>
                log.debug("Increment {}'s refcount for {} {} to {}",
                    this, resourceType, id, refCount + 1)
                idToRefCount.put(id, refCount + 1)
            case None =>
                waitingForResources += 1
                log.debug(
                    "{} now tracking IPAddrGroup {}, waiting for {} resources.",
                    this, id, waitingForResources)
                VirtualTopologyActor ! reqFactory(id)
                idToRefCount.put(id, 1)
        }

    }

    /**
     * Decrement the refcount for a Chain on which this chain depends
     * via a JumpRule. If decrementing to zero, release the Chain and
     * unsubscribe from its updates.
     */
    private def decrChainRefCount(id: UUID): Unit = {
        decrRefCountHelper(id, "Chain", idToChain)
    }

    /**
     * Decrement the refcount for an IPAddrGroup on which this chain
     * depends via a Rule's Condition. If decrementing to zero,
     * release the IPAddrGroup and unsubscribe from its updates.
     */
    private def decrIPAddrGroupRefCount(id: UUID): Unit = {
        decrRefCountHelper(id, "IPAddrGroup", idToIPAddrGroup)
    }

    private def decrRefCountHelper(refId: UUID, refType: String,
                                   idToResource: mutable.Map[UUID, _]): Unit = {
        idToRefCount.get(refId) match {
            case Some(refCount) if refCount > 1 =>
                val newRefCount = refCount - 1
                log.debug("Decrementing {}'s refcount for {} {} to {}",
                    this, refType, refId, newRefCount)
                idToRefCount.put(refId, newRefCount)

            case Some(refCount) if refCount == 1 =>
                // That was the last reference, so stop tracking this resource.
                VirtualTopologyActor ! Unsubscribe(id)
                idToRefCount.remove(refId)
                idToResource.remove(refId) match {
                    // If it wasn't in the cache we must have been waiting for it.
                    case None => waitingForResources -= 1
                    case _ =>  // do nothing
                }
                log.debug(
                    "{} no longer tracking {} {}, waiting for {} resources.",
                    this, refType, refId, waitingForResources)

            case unexpected =>
                log.error("Attempted to decrement {}'s refcount for ID: {}. " +
                          "Expected positive refcount, got {}",
                          this, refId, unexpected)
        }
    }

    /**
     * Increments refcounts for objects on which the specified Rule
     * depends, namely Chains or IPAddrGroups
     */
    private def incrRefCountsForRule(r: Rule): Unit = {
        log.debug("Incrementing refcounts for {} in Chain {}", r, id)

        // Increment refcounts for any Chain dependencies.
        if (r.isInstanceOf[JumpRule]) {
            val targetId = r.asInstanceOf[JumpRule].jumpToChainID
            if (targetId == null)
                log.warning("New jump rule with null target {}", r)
            else
                incrChainRefCount(targetId)
        }

        // Increment refcounts for any IPAddrGroup dependencies.
        val cond = r.getCondition
        if (cond.ipAddrGroupIdDst != null) {
            incrIPAddrGroupRefCount(cond.ipAddrGroupIdDst)
        }
        if (cond.ipAddrGroupIdSrc != null) {
            incrIPAddrGroupRefCount(cond.ipAddrGroupIdSrc)
        }
    }

    /**
     * Decrements refcounts for objects on which the specified Rule
     * depends, namely Chains or IPAddrGroups
     */
    private def decrRefCountsForRule(r: Rule): Unit = {
        log.debug("Decrementing refcounts for {} in Chain {}", r, id)

        // Decrement refcounts for any Chain dependencies.
        if (r.isInstanceOf[JumpRule]) {
            val targetId = r.asInstanceOf[JumpRule].jumpToChainID
            if (targetId == null)
                log.warning("Old jump rule with null target {}", r)
            else
                decrChainRefCount(targetId)
        }

        // Decrement refcounts for any IPAddrGroup dependencies.
        val c = r.getCondition
        if (c.ipAddrGroupIdDst != null) {
            decrIPAddrGroupRefCount(c.ipAddrGroupIdDst)
        }
        if (c.ipAddrGroupIdSrc != null) {
            decrIPAddrGroupRefCount(c.ipAddrGroupIdSrc)
        }
    }

    private def updateRules(newRules: util.List[Rule]): Unit = {
        if (log.isDebugEnabled)
            log.debug("{} received updated rules: {}",
                      this, newRules.mkString(", "));

        // Increment refcounts for rules being added.
        newRules.filterNot(rules.contains).foreach(incrRefCountsForRule)

        // Decrement refcounts for rules being removed.
        rules.filterNot(newRules.contains).foreach(decrRefCountsForRule)

        rules = newRules

        // Send the VirtualTopologyActor an updated chain.
        publishUpdateIfReady()
    }

    private def updateIpAddrGroup(ipAddrGroup: IPAddrGroup) {
        log.debug("{} received update: {}", this, ipAddrGroup)

        // Update rules that reference this IPAddrGroup.
        for (r <- rules) {
            val cond = r.getCondition
            if (cond.ipAddrGroupIdDst == ipAddrGroup.id)
                cond.ipAddrGroupDst = ipAddrGroup
            if (cond.ipAddrGroupIdSrc == ipAddrGroup.id)
                cond.ipAddrGroupSrc = ipAddrGroup
        }

        if (idToIPAddrGroup.put(ipAddrGroup.id, ipAddrGroup).isEmpty)
            waitingForResources -= 1
        publishUpdateIfReady()
    }

    /**
     * Publishes the updated Chain to the VTA iff it's no longer
     * waiting for any responses from Zookeeper.
     */
    private def publishUpdateIfReady() {
        if (isNotWaitingForResource) {
            log.debug("Publishing Chain {} to VTA.", id)
            VirtualTopologyActor ! createChain()
            sendInvalidationIfNeeded()
        } else {
            log.debug("Not publishing Chain {}. Still waiting for {} resources",
                      id, waitingForResources)
        }
    }

    private def sendInvalidationIfNeeded() {
        if (publishingNeedsInvalidation) {
            VirtualTopologyActor !
                InvalidateFlowsByTag(FlowTagger.tagForDevice(id))
            publishingNeedsInvalidation = false
        }
    }

    private def withInvalidation(block: => Unit) {
        publishingNeedsInvalidation = true
        block
    }

    private def createChain() = {
        val eventStream = context.system.eventStream
        val name = chainName getOrElse "unknown"
        new Chain(id, rules.toList, idToChain.toMap, name, eventStream)
    }

    private def updateJumpChain(chain: Chain): Unit = {
        if (!idToRefCount.contains(chain.id)) {
            log.debug("{} ignoring update for Chain {} " +
                      "because its refcount is 0", this, chain.id)
            return
        }

        idToChain.put(chain.id, chain) match {
            case None =>
                waitingForResources -= 1
                log.debug("{} received new Chain {}. Now waiting for {} " +
                          "resources", this, chain.id, waitingForResources)
            case Some(_) =>
                log.debug("{} received updated Chain {}", this, chain.id)
        }
        publishUpdateIfReady()
    }

    /** Updates the name of the managed chain. Publication of the chain should
     *  not happen in the general case, because for certain message ordering in
     *  the manager mailbox, it is possible that the name gets updated before
     *  the manager ever has the occation to set the waitingForResources value
     *  to something else than 0. Therefore, publication can only happen if
     *  the manager was left waiting for the chain name only. */
    private def updateChainName(name: String): Unit = {
        chainName = Some(name)
        if (waitingForName) {
            waitingForName = false
            publishUpdateIfReady()
        }
    }

    /** Check if the chain can be published. In case that the chain name is not
     *  already set, the associated flag is set to true. */
    private def isNotWaitingForResource: Boolean = {
        if (chainName.isEmpty) {
            waitingForName = true
            return false
        }
        0 == waitingForResources
    }

    override def receive = {
        // Each of these message types notifies us of an update to one
        // of the Chain's dependencies. In particular, note that the
        // Chain that can be received is not the Chain managed
        // directly by this ChainManager, but rather a Chain which is
        // a target of one of this Chain's JumpRules.
        case RulesUpdate(rules) => withInvalidation { updateRules(rules) }
        case ChainName(name) => updateChainName(name)
        case chain: Chain => withInvalidation { updateJumpChain(chain) }
        case ipAddrGroup: IPAddrGroup =>
            withInvalidation { updateIpAddrGroup(ipAddrGroup) }
        case unexpected =>
            log.error("{} received an unexpected message: {}", this, unexpected)
    }
}
