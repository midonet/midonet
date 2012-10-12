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

    private def incrChainRefCount(ruleId: UUID): Unit = {
        if (idToRefCount.contains(ruleId)) {
            idToRefCount.put(ruleId, idToRefCount(ruleId) + 1)
        } else {
            // Subscribe to this new chain
            context.actorFor("..").tell(ChainRequest(ruleId, true))
            idToRefCount.put(ruleId, 1)
            waitingForChains += 1
        }
    }

    private def decrChainRefCount(ruleId: UUID): Unit = {
        if (idToRefCount.contains(ruleId)) {
            val refCount = idToRefCount(ruleId)
            if (refCount > 1)
                idToRefCount.put(ruleId, refCount - 1)
            else {
                context.actorFor("..").tell(ChainUnsubscribe(ruleId))
                idToRefCount.remove(ruleId)
                idToChain.remove(ruleId) match {
                    case None => waitingForChains -= 1
                    case Some(chain) =>  // do nothing
                }
            }
        }
        // Else we have some serious bug...
    }

    var rules: util.Collection[Rule] = null
    var sortedRules: util.List[Rule] = null

    private def updateRules(curRules: util.Collection[Rule]): Unit = {
        for (r <- curRules) {
            if ((null == rules || !rules.contains(r))
                    && r.isInstanceOf[JumpRule])
                incrChainRefCount(r.asInstanceOf[JumpRule].jumpToChainID)
        }
        if (null != rules)
            for (r <- rules) {
                if (!curRules.contains(r) && r.isInstanceOf[JumpRule])
                    decrChainRefCount(r.asInstanceOf[JumpRule].jumpToChainID)
            }
        rules = curRules
        sortedRules =
            rules.toList.sortWith((l: Rule, r: Rule) => l.compareTo(r) < 0)
        // Send the VirtualTopologyActor an updated chain.
        if (0 == waitingForChains)
            context.actorFor("..").tell(
                new Chain(id, sortedRules, idToChain.toMap,
                    "TODO: need name"))
    }

    private def chainUpdate(chain: Chain): Unit = {
        idToRefCount.get(chain.id) match {
            case None =>  // we don't care about this chain anymore
            case Some(count) =>
                idToChain.put(chain.id, chain) match {
                    case None =>
                        waitingForChains -= 1
                        if (0 == waitingForChains)
                            context.actorFor("..").tell(
                                new Chain(id, rules.toList, idToChain.toMap,
                                          "TODO: need name"))
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
