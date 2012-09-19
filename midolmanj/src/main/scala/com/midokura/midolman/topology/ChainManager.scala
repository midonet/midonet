/*
 * Copyright 2012 Midokura Europe SARL
 */
package com.midokura.midolman.topology

import akka.actor.Actor
import collection.JavaConversions._
import collection.mutable
import java.util.UUID

import com.midokura.midolman.simulation.Chain
import com.midokura.midolman.state.zkManagers.ChainZkManager.ChainConfig
import com.midokura.midolman.rules.{JumpRule, Rule}
import com.midokura.midolman.topology.VirtualTopologyActor.{ChainRequest,
                                                            ChainUnsubscribe}


class ChainManager(val id: UUID) extends Actor {
    // Kick off the first attempt to construct the device.
    val cfg: ChainConfig = null //chainMgr.get(id)
    // TODO(pino): what if the cfg is null?
    updateRules()

    case object Refresh

    val cb: Runnable = new Runnable() {
        def run() {
            // CAREFUL: this is not run on this Actor's thread.
            self.tell(Refresh)
        }
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

    var idToRule = mutable.Map[UUID, Rule]()
    var rules: mutable.MutableList[Rule] = null

    private def updateRules(): Unit = {
        val ruleIds: List[UUID] = null; // ruleMgr.getRuleIds(id, cb);
        rules = mutable.MutableList[Rule]()
        for (ruleIds <- ruleIds) {
            rules += (idToRule.get(ruleIds) match {
                case None =>
                    val r: Rule = null //ruleMgr.get(ruleIds)
                    idToRule.put(ruleIds, r)
                    if (r.isInstanceOf[JumpRule])
                        incrChainRefCount(
                            r.asInstanceOf[JumpRule].jumpToChainID)
                    r // return the rule
                case Some(rule) => rule
            })
        }
        // Sort the rule array
        rules.sortWith((l: Rule, r: Rule) => l.compareTo(r) < 0)

        // Throw away Rules we no longer need.
        val filter = (kv: (UUID, Rule)) => ruleIds.contains(kv._1)
        val removedRules = idToRule.filterNot(filter)
        idToRule = idToRule.filter(filter)
        // Unsubscribe from any chains we used to jump to.
        for (kv <- removedRules) {
            if (kv._2.isInstanceOf[JumpRule]) {
                decrChainRefCount(kv._2.asInstanceOf[JumpRule].jumpToChainID)
            }
        }
        // Finally, send the VirtualTopologyActor an updated chain.
        if (0 == waitingForChains)
            context.actorFor("..").tell(
                new Chain(id, rules.toList, idToChain.toMap, cfg.name))
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
                                          cfg.name))
                    case _ =>  // Nothing else to do.
                }
        }
    }

    def receive = {
        case Refresh => updateRules()
        case chain: Chain => chainUpdate(chain)
    }
}
