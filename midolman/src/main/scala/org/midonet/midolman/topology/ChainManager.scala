/*
 * Copyright 2012 Midokura Europe SARL
 */
package org.midonet.midolman.topology

import akka.actor.{ActorSystem, ActorRef, Actor}
import collection.JavaConversions._
import collection.mutable
import java.util.UUID

import org.midonet.midolman.simulation.Chain
import org.midonet.midolman.rules.{JumpRule, Rule}
import org.midonet.midolman.topology.VirtualTopologyActor.{ChainRequest,
                                                            ChainUnsubscribe}
import org.midonet.cluster.Client
import org.midonet.cluster.client.ChainBuilder
import java.util
import org.midonet.midolman.topology.ChainManager.TriggerUpdate
import org.midonet.midolman.FlowController.InvalidateFlowsByTag
import org.midonet.midolman.logging.ActorLogWithoutPath

object ChainManager {
    case class TriggerUpdate(rules: util.List[Rule])
}

class ChainManager(val id: UUID, val clusterClient: Client) extends Actor
        with ActorLogWithoutPath {
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
            VirtualTopologyActor.getRef() ! ChainRequest(chainId, update = true)
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
                VirtualTopologyActor.getRef() ! ChainUnsubscribe(chainId)
                idToRefCount.remove(chainId)
                idToChain.remove(chainId) match {
                    case None => waitingForChains -= 1
                    case Some(chain) =>  // do nothing
                }
            }
        }
        // Else we have some serious bug...
    }

    var rules: util.List[Rule] = null

    private def updateRules(curRules: util.List[Rule]): Unit = {
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
            publishUpdate(new Chain(id, rules, idToChain.toMap,
                                    "TODO: need name", context.system.eventStream))
        }
    }

    private def publishUpdate(chain: Chain) {
        VirtualTopologyActor.getRef() ! chain
        VirtualTopologyActor.getRef() !
                InvalidateFlowsByTag(FlowTagger.invalidateFlowsByDevice(id))
    }

    private def chainUpdate(chain: Chain): Unit = {
        idToRefCount.get(chain.id) match {
            case None =>  // we don't care about this chain anymore
            case Some(_) =>
                // If this is the first Chain object we see for its ID, then
                // we were waiting for it to build the chain we're managing.
                if (idToChain.put(chain.id, chain).isEmpty)
                    waitingForChains -= 1
                // In either case, if we're not waiting for other jump-to
                // chains, send an update for the chain we manage.
                if (0 == waitingForChains) {
                    publishUpdate(new Chain(id, rules.toList, idToChain.toMap,
                                            "TODO: need name", context.system.eventStream))
                }
        }
    }

    override def receive = {
        case TriggerUpdate(rules) => updateRules(rules)
        case chain: Chain => chainUpdate(chain)
    }
}

class ChainBuilderImpl(val chainMgr: ActorRef) extends ChainBuilder {
    def setRules(rules: util.List[Rule]) {
        chainMgr ! TriggerUpdate(rules)
    }

    def setRules(ruleOrder: util.List[UUID], rules: util.Map[UUID, Rule]) {
        val orderedRules = ruleOrder.map(x => rules.get(x))
        setRules(orderedRules)
    }
}
