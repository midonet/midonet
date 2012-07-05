/*
 * Copyright 2012 Midokura Europe SARL
 */
package com.midokura.midolman.vrn

import akka.actor.Actor
import collection.JavaConversions._
import collection.mutable
import java.util.UUID
import com.midokura.midolman.state.{RuleZkManager, ChainZkManager}
import com.midokura.midolman.state.ChainZkManager.ChainConfig
import com.midokura.midolman.rules.{JumpRule, Rule}

class ChainManager(val id: UUID, val chainMgr: ChainZkManager,
                   val ruleMgr: RuleZkManager) extends Actor {
    // Kick off the first attempt to construct the device.
    val cfg: ChainConfig = chainMgr.get(id)
    // TODO(pino): what if the cfg is null?
    updateRules()

    case object Refresh
    val cb: Runnable = new Runnable() {
        def run() {
            // CAREFUL: this is not run on this Actor's thread.
            self.tell(Refresh)
        }
    }

    var idToRule = mutable.Map[UUID, Rule]()
    private def updateRules(): Unit = {
        val ruleIds = ruleMgr.getRuleIds(id, cb);
        var rules = mutable.MutableList[Rule]()
        var i = 0
        for (ruleIds <- ruleIds) {
            rules(i) = idToRule.get(ruleIds) match {
                    case None =>
                        val r = ruleMgr.get(ruleIds)
                        idToRule.put(ruleIds, r)
                        if (r.isInstanceOf[JumpRule])
                            context.actorFor("..").tell(ChainRequest(
                                r.asInstanceOf[JumpRule].jumpToChainID, true))
                        r // return the rule
                    case Some(rule) => rule
            }
            i += 1
        }
        // Sort the rule array
        rules.sortWith( (l: Rule, r: Rule) => l.compareTo(r) < 0 )

        // Throw away Rules we no longer need.
        val filter = (kv: (UUID, Rule)) => ruleIds.contains(kv._1)
        val removedRules = idToRule.filterNot(filter)
        idToRule = idToRule.filter(filter)
        // Unsubscribe from any chains we used to jump to.
        for (kv <- removedRules) {
            if (kv._2.isInstanceOf[JumpRule]) {
                context.actorFor("..").tell(ChainUnsubscribe(
                    kv._2.asInstanceOf[JumpRule].jumpToChainID))
            }
        }
        // Finally, send the VirtualTopologyActor an updated chain.
        context.actorFor("..").tell(new Chain(id, rules.toList))
    }

    def receive = {
        case Refresh => updateRules()
        case chain: Chain =>
            ; // Do nothing. We subscribe to 'jumpTo' chains just to force
            // pre-loading and to keep them loaded.

    }
}
