/*
 * Copyright 2012 Midokura Europe SARL
 */
package com.midokura.midolman.topology

import akka.actor.{ActorLogging, ActorRef, Actor}
import collection.JavaConversions._
import collection.mutable
import java.util.UUID

import com.midokura.midolman.simulation.Chain
import com.midokura.midolman.rules.{JumpRule, Rule}
import com.midokura.midolman.topology.VirtualTopologyActor.{ChainRequest,
                                                            ChainUnsubscribe}
import com.midokura.midonet.cluster.Client
import com.midokura.midonet.cluster.client.ChainBuilder
import java.util
import com.midokura.midolman.topology.ChainManager.TriggerUpdate
import com.midokura.midolman.FlowController
import com.midokura.midolman.FlowController.InvalidateFlowsByTag

object ChainManager {
    case class TriggerUpdate(rules: util.Collection[Rule])
}

class ChainManager(val id: UUID, val clusterClient: Client) extends Actor
        with ActorLogging {
    import ChainManager._

    override def preStart() {
        clusterClient.getChain(id, new ChainBuilderImpl(self))
    }

    // Store the chains that these rules jump to.
    private val idToChain = mutable.Map[UUID, Chain]()
    // Store the number of rules that jump to each chain.
    private val idToRefCount = mutable.Map[UUID, Int]()
    // Keep track of how many chains are missing from the map.
    private var waitingForChains: Int = 0

    private def incrChainRefCount(chainId: UUID): Unit = {
        if (idToRefCount.contains(chainId)) {
            idToRefCount.put(chainId, idToRefCount(chainId) + 1)
        } else {
            // Subscribe to this new chain
            context.actorFor("..").tell(ChainRequest(chainId, true), self)
            idToRefCount.put(chainId, 1)
            waitingForChains += 1
        }
    }

    private def decrChainRefCount(chainId: UUID): Unit = {
        if (idToRefCount.contains(chainId)) {
            val refCount = idToRefCount(chainId)
            if (refCount > 1)
                idToRefCount.put(chainId, refCount - 1)
            else {
                context.actorFor("..").tell(ChainUnsubscribe(chainId), self)
                idToRefCount.remove(chainId)
                idToChain.remove(chainId) match {
                    case None => waitingForChains -= 1
                    case Some(chain) =>  // do nothing
                }
            }
        }
        // Else we have some serious bug...
    }

    var rules: util.Collection[Rule] = null

    private def updateRules(curRules: util.Collection[Rule]): Unit = {
        for (r <- curRules) {
            if ((null == rules || !rules.contains(r))
                    && r.isInstanceOf[JumpRule]) {
                val targetId = r.asInstanceOf[JumpRule].jumpToChainID
                targetId match {
                    case null =>
                        log.warning("New jump rule with null target {}", r)
                    case _ =>
                        incrChainRefCount(targetId)
                }
            }
        }
        if (null != rules)
            for (r <- rules) {
                if (!curRules.contains(r) && r.isInstanceOf[JumpRule]) {
                    val targetId = r.asInstanceOf[JumpRule].jumpToChainID
                    targetId match {
                        case null =>
                            log.warning("Old jump rule with null target {}", r)
                        case _ =>
                            decrChainRefCount(targetId)
                    }
                }
            }
        rules = curRules
        // Sort in the Chain itself. Java conversions mess up the sort!
        //val sortedRules =
        //    rules.toList.sortWith((l: Rule, r: Rule) => l.compareTo(r) < 0)

        // Send the VirtualTopologyActor an updated chain.
        if (0 == waitingForChains) {
            context.actorFor("..").tell(
                new Chain(id, rules.toBuffer[Rule], idToChain.toMap,
                    "TODO: need name"))
        // invalidate all flow for this chain
        FlowController.getRef() !
            InvalidateFlowsByTag(FlowTagger.invalidateFlowsByDevice(id))
        }
    }

    private def chainUpdate(chain: Chain): Unit = {
        idToRefCount.get(chain.id) match {
            case None =>  // we don't care about this chain anymore
            case Some(count) =>
                idToChain.put(chain.id, chain) match {
                    case None =>
                        waitingForChains -= 1
                        if (0 == waitingForChains) {
                            context.actorFor("..").tell(
                                new Chain(id, rules.toList, idToChain.toMap,
                                          "TODO: need name"))
                        // invalidate all flow for this chain
                        FlowController.getRef() !
                            InvalidateFlowsByTag(FlowTagger.invalidateFlowsByDevice(id))
                        }
                    case _ =>  // Nothing else to do.
                }
        }
    }

    override def receive = {
        case TriggerUpdate(rules) => updateRules(rules)
        case chain: Chain => chainUpdate(chain)
    }
}

class ChainBuilderImpl(val chainMgr: ActorRef) extends ChainBuilder {
    def setRules(rules: util.Collection[Rule]) {
        chainMgr.tell(TriggerUpdate(rules))
    }
}
