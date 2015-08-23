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

package org.midonet.cluster.models

import java.util.UUID

import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

import org.midonet.cluster.data.storage._
import org.midonet.cluster.models.Commons.Condition
import org.midonet.cluster.models.Topology.Rule.{JumpRuleData, RedirRuleData}
import org.midonet.cluster.models.Topology._
import org.midonet.cluster.util.UUIDUtil._

/*
 * When a packet ingresses/egresses a port that has associated ServiceChains
 * for that direction (in/out):
 * 1. The packet traverses the port's rule-chain (inbound or outbound)
 * 2. The match conditions in the port's chain will be explained below, but the
 *    packet jumps to the next ServiceChain's classifier-rule-chain for
 *    classifier evaluation.
 * 3. If the packet matches any classifier, then it jumps to the ServiceChain's
 *    element-rule-chain.
 *    a. Otherwise, there's a catch-all rule at the end that appends a special
 *       vlan tag (explained later) and CONTINUE (in order to "return" from the
 *       jump and be back at step #2).
 * 4. A rule for an element matches the previous element's service-port as
 *    ingress port, pops the vlan, pushes the vlan for the next element and
 *    redirects the packet out of the service-port.
 *    a. The rule for the first element should match the ServiceChain's
 *       protected port as ingress port.
 *    b. The element rule chain is not re-evaluated after the final service
 *       is traversed, so we don't need to signal "no-redirect" as we do for
 *       the port chain (see below).
 *    c. The last element's service-port is matched by a rule that jumps to the
 *       port's rule-chain in order to go back to step #2.
 * 5. If the service re-emits the packet, it ingresses the service-port and
 *    traverses its chain-identification rules. A rule will match the vlan, and
 *    jump to the service's element-rule-chain to be back at step #4.
 *
 * Now we explain matching in the port's chain. The goal is to be able to
 * traverse multiple ServiceChains and not get caught in an infinite loop.
 * We achieve this by encoding a ServiceChain's position using a reserved range
 * of vlan values. Each port rule matches the position of the previous
 * ServiceChain, pops the vlan,  and jumps to the classifier-rule-chain of the
 * next ServiceChain. Any part of the ServiceChain's work-flow that leads back
 * to #2 (i.e. both #3.a and #4.c) must push the appropriate vlan tag. (Again,
 * this is only used for signaling between rule-chains at simulation-time).
 *
 * Finally, these three rules are at the end of the port's inbound chain:
 * 6. A rule that matches the last ServiceChain's "position-tag", pops
 *    it, appends a special "no-redirect" tag and redirects the packet into
 *    the inspected port. We can't just CONTINUE/ACCEPT because we might be
 *    in the rule-chain evaluation of a service port.
 * 7. A rule that matches the "no-redirect" tag, pops it, and ACCEPTs.
 * 8. A rule that doesn't match a vlan, and jumps to the classifier-rule-chain
 *    of the *first* ServiceChain. This rule must be placed last.
 *
 * And at the end of a port's outbound chain:
 * 9. A rule that matches the last ServiceChain's "position-tag", pops
 *    it, and redirects the packet out of the inspected port.
 * 10.The Jump rule for the *first*. Same as #8.
 *
 * The "no-redirect" tag is not needed in the outbound direction because
 * packets redirected OUT a port do not traverse any more chains. Packets
 * redirected IN a port re-traverse its chains. That's why the redirection is
 * the first chains to be traversed inbound. Also, you cannot have 2 redirection
 * chains in the same direction on a port:
 * - on outbound, one chain would be skipped
 * - on inbound, there's no signaling to keep track of which chain has been
 *   evaluated. An infinite loop would result. You can see the algorithm above
 *   is very complex because it tries to accommodate many ServiceChains for
 *   the same port.
 */
object ServiceChainTranslation {

    private final val Timeout = 5 seconds

    final class FutureOps[T](val future: Future[T]) extends AnyVal {

        def getOrThrow: T = Await.result(future, Timeout)
    }

    implicit def toFutureOps[U](future: Future[U]): FutureOps[U] = {
        new FutureOps(future)
    }

    implicit object ElementOrdering extends Ordering[ServiceChainElem] {
        // Elements should be sorted by position, tie-break by Service UUID
        def compare(left: ServiceChainElem, right: ServiceChainElem) = {
            var ret: Int = left.getPosition compare right.getPosition
            if (ret == 0) {
                ret = left.getServiceId.toString compare right.getServiceId.toString
            }
            ret
        }
    }

    implicit object ChainOrdering extends Ordering[ServiceChain] {
        // Chains should be sorted by position, which is unique
        def compare(left: ServiceChain, right: ServiceChain) = {
            left.getPosition compare right.getPosition
        }
    }

    def setConditions(ruleBuilder: Rule.Builder,
                      fn: (Condition.Builder) => Unit): Rule.Builder = {
        fn(ruleBuilder.getConditionBuilder)
        ruleBuilder
    }

    def updateChain(store: Storage, op: PersistenceOp): Boolean = {
        var ops = Seq.empty[PersistenceOp]
        op match {
            case CreateOp(i: ServiceChain) =>
                // Don't allow service chains on service ports
                val port = store.get(classOf[Port], i.getPort).getOrThrow
                if (port.hasService2)
                    return false
                val classifiersBuilder = Chain.newBuilder().setId(UUID.randomUUID)
                    .setName("SfcClassifiers" + i.getId.toString)
                val elems = Chain.newBuilder().setId(UUID.randomUUID)
                    .setName("SfcElems" + i.getId.toString).build
                ops = ops :+ CreateOp(elems)
                val serviceChainBuilder = i.toBuilder
                    .setClassifierChain(classifiersBuilder.getId)
                    .setElemChain(elems.getId)
                ops = ops ++ synchronizeClassifiers(serviceChainBuilder,
                                                    classifiersBuilder, elems)
                ops = ops :+ CreateOp(classifiersBuilder.build)
                // Synchronize the port's chain
                // TODO: make this play nice with L2Insertion translation
                val (portIn, portOut) = L2InsertionTranslation
                    .ensureRedirectChains(store, i.getPort)
                // The new ServiceChain's position must be unique
                val serviceChains = getServiceChains(store, i.getPort)
                if (serviceChains.exists(_.getPosition == i.getPosition))
                    return false
                ops = ops ++ synchronizePortChain(store, if (i.getPortIngress)
                    portIn else portOut, serviceChains :+ i)
                ops = ops :+ CreateOp(serviceChainBuilder.build())

            case UpdateOp(i: ServiceChain, _) =>
                val old = store.get(
                    classOf[ServiceChain], i.getId).getOrThrow
                // Don't allow changing the ServiceChain's port or the direction
                if (i.getPort != old.getPort ||
                    i.getPortIngress != old.getPortIngress)
                    return false
                // Copy the translation references
                val serviceChainBuilder = i.toBuilder
                    .setClassifierChain(old.getClassifierChain)
                    .setElemChain(old.getElemChain)
                val classifiersBuilder = store.get(
                    classOf[Chain], old.getClassifierChain).getOrThrow.toBuilder
                val elems = store.get(
                    classOf[Chain], old.getElemChain).getOrThrow
                ops = ops ++ synchronizeClassifiers(serviceChainBuilder,
                                                    classifiersBuilder, elems)
                ops = ops :+ UpdateOp(classifiersBuilder.build)
                val (portIn, portOut) = L2InsertionTranslation
                    .ensureRedirectChains(store, i.getPort)
                // Update port's chain if the ServiceChain position changed
                if (i.getPosition != old.getPosition) {
                    val serviceChains = getServiceChains(
                        store, i.getPort).filter(_.getId != i.getId)
                    if (serviceChains.exists(_.getPosition == i.getPosition))
                        return false
                    ops = ops ++ synchronizePortChain(store,
                        if (i.getPortIngress) portIn else portOut,
                        serviceChains :+ i)
                }
                ops = ops :+ UpdateOp(serviceChainBuilder.build())

            case DeleteOp(clazz, id, ignoreMissing) =>
                if (clazz != classOf[ServiceChain])
                    return false
                // Don't allow deleting the ServiceChain if it's referenced by
                // any element.
                try {
                    val old = store.get(
                        classOf[ServiceChain], id).getOrThrow
                    if (old.getElemIdsCount > 0)
                        return false
                    // Delete the ServiceChain and its rule chains
                    // TODO: are the classifier rules automatically deleted?
                    ops = ops :+ DeleteOp(classOf[Chain], old.getClassifierChain)
                    // There shouldn't be any element rules
                    ops = ops :+ DeleteOp(classOf[Chain], old.getElemChain)
                    ops = ops :+ op
                    val (portIn, portOut) = L2InsertionTranslation
                        .ensureRedirectChains(store, old.getPort)
                    // Update port's chain because ServiceChain order changed
                    val serviceChains = getServiceChains(
                        store, old.getPort).filter(_.getId != old.getId)
                    ops = ops ++ synchronizePortChain(store,
                        if (old.getPortIngress) portIn else portOut,
                        serviceChains)
                    // Leave port's chains even after deleting last ServiceChain
                }
                catch {
                    case e: Exception =>
                        ignoreMissing match {
                            case true => return true
                            case false => return false
                        }
                }
            case _ =>
                return false
        }
        if (ops.size > 0)
            store.multi(ops)
        true
    }

    def getServiceChains(store: Storage, portId: UUID): Seq[ServiceChain] = {
        val port = store.get(classOf[Port], portId).getOrThrow
        var serviceChains = store.getAll(
            classOf[ServiceChain],
            port.getServiceChainsList.asScala).getOrThrow
        serviceChains
    }

    def synchronizeElementChain(store: Storage,
                                serviceChain: ServiceChain,
                                elementRuleChain: Chain,
                                elems: Seq[ServiceChainElem]
                                ): Seq[PersistenceOp] = {
        var ops = Seq.empty[PersistenceOp]
        // Delete all the rules
        ops = ops ++ elementRuleChain.getRuleIdsList.asScala.map {
            DeleteOp(classOf[Rule], _)
        }
        // We need the chain's builder to explicitly control rule order
        val elementChainBuilder = elementRuleChain.toBuilder.clearRuleIds()
        // Now sort all the Elements, and create the Redirect rules
        var previousElem: ServiceChainElem = null
        var previousService: Service = null
        elems.sorted.foreach({ nextElem =>
            val nextService = store.get(
                classOf[Service], nextElem.getServiceId).getOrThrow
            val condBuilder = Condition.newBuilder
            val ruleBuilder = Rule.newBuilder
                .setId(UUID.randomUUID()).setChainId(elementRuleChain.getId)
            // If there was a previous service, then match its port
            if (previousService == null)
                condBuilder.addInPortIds(previousService.getPort)
            else
                condBuilder.addInPortIds(serviceChain.getPort)
            if (previousElem.hasVlan) // && previousElem.getVlan != 0)
                ruleBuilder.setPopVlan(true)
            if (nextElem.hasVlan) // && nextElem.getVlan != 0)
                ruleBuilder.setPushVlan(nextElem.getVlan)
            // If this is a routed/L3 service, change the packet's MAC
            if (!nextElem.hasMac) {
                // TODO: enhance rule so that it can set a packet's dst MAC
                // for the routed service use case.
                // ruleBuilder.modifyMac(nextService.getMac)
            }
            val rule = ruleBuilder.setCondition(condBuilder.build)
                .setType(Rule.Type.REDIRECT_RULE)
                .setAction(Rule.Action.REDIRECT)
                .setRedirRuleData(
                    RedirRuleData.newBuilder
                        .setTargetPort(nextService.getPort)
                        .setIngress(false)
                        .setFailOpen(nextElem.getFailOpen).build)
                .build
            ops = ops :+ CreateOp(rule)
            elementChainBuilder.addRuleIds(rule.getId)
            previousElem = nextElem
            previousService = nextService
        })
        if (previousElem == null) {
            ops = ops :+ UpdateOp(elementChainBuilder.build)
            return ops // there are no service chains
        }
        // Finally, add rules to handle the return from the last service port,
        // We're going to jump to the port chain with a vlan tag that encodes
        // the ServiceChain's "position-tag". See the object's doc above.
        val (portIn, portOut) = L2InsertionTranslation
            .ensureRedirectChains(store, serviceChain.getPort)
        val rule = createJumpRule(
            Condition.newBuilder.addInPortIds(previousService.getPort).build,
            elementChainBuilder,
            if (serviceChain.getPortIngress) portIn else portOut,
            popVlan = previousElem.hasVlan)
        ops = ops :+ CreateOp(rule)
        elementChainBuilder.addRuleIds(rule.getId)
        ops = ops :+ UpdateOp(elementChainBuilder.build)
        ops
    }

    def synchronizePortChain(store: Storage, ruleChain: Chain,
                             serviceChains: Seq[ServiceChain]
                                ): Seq[PersistenceOp] = {
        var ops = Seq.empty[PersistenceOp]
        // Delete the rules in the port's chain.
        ops = ops ++ ruleChain.getRuleIdsList.asScala.map {
            DeleteOp(classOf[Rule], _)
        }
        // IMPORTANT: we need the ruleChainBuilder in order to control the order
        // of evaluation of the chain's rules.
        val ruleChainBuilder = ruleChain.toBuilder.clearRuleIds()

        // Now sort all the ServiceChains for the inspected port, and create
        // the Jump rules to their classifier-rule-chains.
        var previous: ServiceChain = null
        var firstChainsRule: Rule = null
        serviceChains.sorted.foreach({ next =>
            val condBuilder = Condition.newBuilder
            if (previous != null)
                condBuilder.setVlan(encodePosition(next.getPosition))
            val classifierChain = store.get(
                classOf[Chain], next.getClassifierChain).getOrThrow
            val rule = createJumpRule(
                condBuilder.build, ruleChainBuilder, classifierChain, true)
            ops = ops :+ CreateOp(rule)
            if (previous == null) {
                firstChainsRule = rule
                // Remove the rule's ID from the list, later add it at the end
                ruleChainBuilder.removeRuleIds(0)
            }
            previous = next
        })
        // Now we create the rules described in #6-10
        if (previous == null) {
            ops = ops :+ UpdateOp(ruleChainBuilder.build)
            return ops // there are no service chains
        }
        if (!previous.getPortIngress) {
            // outbound direction, #9 in description above
            var rule = Rule.newBuilder()
                .setId(UUID.randomUUID.asProto)
                .setChainId(ruleChain.getId)
                .setCondition(Condition.newBuilder.setVlan(encodePosition(previous.getPosition)).build)
                .setPopVlan(true)
                .setType(Rule.Type.REDIRECT_RULE)
                .setAction(Rule.Action.REDIRECT)
                .setRedirRuleData(
                    RedirRuleData.newBuilder
                        .setTargetPort(previous.getPort)
                        .setIngress(false)
                        .build)
                .build
            ruleChainBuilder.addRuleIds(rule.getId)
            ops = ops :+ CreateOp(rule)
            // For #10 in description above
            ruleChainBuilder.addRuleIds(firstChainsRule.getId)
            ops = ops :+ UpdateOp(ruleChainBuilder.build)
            return ops
        }
        // Inbound direction....  First #6 in description above
        var rule = Rule.newBuilder()
            .setId(UUID.randomUUID.asProto)
            .setChainId(ruleChain.getId)
            .setCondition(Condition.newBuilder.setVlan(encodePosition(previous.getPosition)).build)
            .setPopVlan(true)
            .setPushVlan(encodeNoRedirect)
            .setType(Rule.Type.REDIRECT_RULE)
            .setAction(Rule.Action.REDIRECT)
            .setRedirRuleData(
                RedirRuleData.newBuilder
                    .setTargetPort(previous.getPort)
                    .setIngress(true)
                    .build)
            .build
        ruleChainBuilder.addRuleIds(rule.getId)
        ops = ops :+ CreateOp(rule)
        // For #7 in description above
        rule = Rule.newBuilder()
            .setId(UUID.randomUUID.asProto)
            .setChainId(ruleChain.getId)
            .setCondition(Condition.newBuilder.setVlan(encodeNoRedirect).build)
            .setPopVlan(true)
            .setType(Rule.Type.LITERAL_RULE)
            .setAction(Rule.Action.ACCEPT)
            .build
        ruleChainBuilder.addRuleIds(rule.getId)
        ops = ops :+ CreateOp(rule)
        // For #8 in description above
        ruleChainBuilder.addRuleIds(firstChainsRule.getId)
        ops = ops :+ UpdateOp(ruleChainBuilder.build)
        ops
    }

    def createJumpRule(c: Condition, chainBuilder: Chain.Builder,
                       targetChain: Chain, popVlan: Boolean = false): Rule = {
        val rule = Rule.newBuilder()
            .setId(UUID.randomUUID.asProto)
            .setCondition(c)
            .setChainId(chainBuilder.getId)
            .setType(Rule.Type.JUMP_RULE)
            .setAction(Rule.Action.JUMP)
            .setPopVlan(popVlan)
            .setJumpRuleData(
                JumpRuleData.newBuilder
                    .setJumpChainId(targetChain.getId)
                    .setJumpChainName(targetChain.getName).build)
            .build
        chainBuilder.addRuleIds(rule.getId)
        rule
    }

    def encodePosition(pos: Int) = (1 << 15) + pos
    def encodeNoRedirect = (1 << 15) + 111

    def synchronizeClassifiers(svcChainBuilder: ServiceChain.Builder,
                               classifiersBuilder: Chain.Builder,
                               elems: Chain): Seq[PersistenceOp] = {
        var ops = Seq.empty[PersistenceOp]
        // Delete the rules in the classifier chain.
        ops = ops ++ classifiersBuilder.getRuleIdsList.asScala.map {
            DeleteOp(classOf[Rule], _)
        }
        classifiersBuilder.clearRuleIds()
        // Now create a new rule for each classifier
        ops = ops ++ svcChainBuilder.getClassifiersList.asScala.map {
            c => CreateOp(createJumpRule(c, classifiersBuilder, elems))
        }
        // Now append a catch-all rule that appends the ServiceChain's
        // "position-tag" and CONTINUEs. See #3.a in description above.
        val rule = Rule.newBuilder()
            .setId(UUID.randomUUID.asProto)
            .setChainId(classifiersBuilder.getId)
            .setPushVlan(encodePosition(svcChainBuilder.getPosition))
            .setType(Rule.Type.LITERAL_RULE)
            .setAction(Rule.Action.CONTINUE)
            .build
        // IMPORTANT: this is why we need the classifiersBuilder. We want to
        // explicitly set this catch-all rule to be the last in the chain.
        classifiersBuilder.addRuleIds(rule.getId)
        ops :+ CreateOp(rule)
    }

    def updateService(store: Storage, op: PersistenceOp): Boolean = {
        var ops = List.empty[PersistenceOp]
        op match {
            case CreateOp(i: Service) =>
                // Make sure that the service port has inbound and
                // outbound redirect ports.
                // TODO: make this play nice with L2Insertion translation
                L2InsertionTranslation.ensureRedirectChains(store, i.getPort)
                ops = ops :+ op
            case UpdateOp(i: Service, _) =>
                val original = store.get(
                    classOf[Service], i.getId).getOrThrow
                // Don't allow changing the service's port
                if (i.getPort != original.getPort)
                    return false
                // TODO? allow updating the service's mac and/or IP
                return false
            case DeleteOp(clazz, id, ignoreMissing) =>
                if (clazz != classOf[Service])
                    return false
                // Don't allow deleting the Service if it's referenced by
                // any Chain element.
                try {
                    val removed = store.get(classOf[Service], id).getOrThrow
                    if (removed.getElemIdsCount > 0)
                        return false
                    ops = ops :+ op
                    // Leave the redirection chains on the service port.
                }
                catch {
                    case e: Exception =>
                        ignoreMissing match {
                            case true => return true
                            case false => return false
                        }
                }
            case _ =>
                return false
        }
        if (ops.size > 0)
            store.multi(ops)
        true
    }

    def updateElements(store: Storage, op: PersistenceOp): Boolean = {
        var ops = Seq.empty[PersistenceOp]
        op match {
            case CreateOp(i: ServiceChainElem) =>
                val serviceChain = store.get(
                    classOf[ServiceChain], i.getChainId).getOrThrow
                val service = store.get(
                    classOf[Service], i.getServiceId).getOrThrow
                val elems = store.getAll(classOf[ServiceChainElem],
                                         serviceChain.getElemIdsList.asScala).getOrThrow
                // No other element for same service may have the same vlan
                if (elems.exists(_.getVlan == i.getVlan))
                    return false
                // No other elem for same SvcChain may have same service port
                if (elems.exists(_.getServiceId == i.getServiceId))
                    return false

                val elementChain = store.get(
                    classOf[Chain], serviceChain.getElemChain).getOrThrow
                ops = ops ++ synchronizeElementChain(store, serviceChain,
                                                     elementChain, elems :+ i)
                val (servicePortChain, _) = L2InsertionTranslation
                    .ensureRedirectChains(store, service.getPort)
                val svcPortChainBuilder = servicePortChain.toBuilder.clearRuleIds()
                val condBuilder = Condition.newBuilder
                if (i.hasMac)
                    if (serviceChain.getPortIngress)
                        condBuilder.setDlSrc(i.getMac)
                    else
                        condBuilder.setDlDst(i.getMac)
                else
                    condBuilder.setVlan(i.getVlan)
                val rule = createJumpRule(condBuilder.build,
                                          svcPortChainBuilder, elementChain)
                val elemBuilder = i.toBuilder.setServiceRule(rule.getId)
                ops = ops :+ CreateOp(rule)
                ops = ops :+ UpdateOp(svcPortChainBuilder.build)
                ops = ops :+ CreateOp(elemBuilder.build)

            case UpdateOp(i: ServiceChainElem, _) =>
                val old = store.get(
                    classOf[ServiceChainElem], i.getId).getOrThrow
                val serviceChain = store.get(
                    classOf[ServiceChain], i.getChainId).getOrThrow
                val service = store.get(
                    classOf[Service], i.getServiceId).getOrThrow

                // Don't allow changing the element's service or chain
                if (i.getChainId != old.getChainId ||
                    i.getServiceId != old.getServiceId)
                    return false
                // Copy the translation references
                val elemBuilder = i.toBuilder.setServiceRule(old.getServiceRule)
                ops = ops :+ UpdateOp(elemBuilder.build)
                if (i.hasMac) {
                    if (!old.hasMac || old.getMac != i.getMac) {
                        val condBuilder = Condition.newBuilder
                        if (serviceChain.getPortIngress)
                            condBuilder.setDlSrc(i.getMac)
                        else
                            condBuilder.setDlDst(i.getMac)
                        val rule = store.get(
                            classOf[Rule], old.getServiceRule).getOrThrow
                        ops = ops :+ UpdateOp(
                            rule.toBuilder.setCondition(condBuilder.build).build)
                    }
                } else if (i.getVlan != old.getVlan) {
                    val rule = store.get(
                        classOf[Rule], old.getServiceRule).getOrThrow
                    ops = ops :+ UpdateOp(rule.toBuilder.setCondition(
                        Condition.newBuilder.setVlan(i.getVlan).build).build)
                }
                val elementChain = store.get(
                    classOf[Chain], serviceChain.getElemChain).getOrThrow
                val elems = store.getAll(
                    classOf[ServiceChainElem], serviceChain.getElemIdsList.asScala)
                    .getOrThrow.filter(_.getId != i.getId)
                ops = ops ++ synchronizeElementChain(store, serviceChain,
                                                     elementChain, elems :+ i)

            case DeleteOp(clazz, id, ignoreMissing) =>
                if (clazz != classOf[ServiceChainElem])
                    return false
                try {
                    val old = store.get(
                        classOf[ServiceChainElem], id).getOrThrow
                    val serviceChain = store.get(
                        classOf[ServiceChain], old.getChainId).getOrThrow
                    val service = store.get(
                        classOf[Service], old.getServiceId).getOrThrow
                    // Delete the Element and its service port ingress rule
                    ops = ops :+ DeleteOp(classOf[Rule], old.getServiceRule)
                    ops = ops :+ op
                    // Update element-rule-chain because element order changed
                    val elementChain = store.get(
                        classOf[Chain], serviceChain.getElemChain).getOrThrow
                    val elems = store.getAll(
                        classOf[ServiceChainElem], serviceChain.getElemIdsList.asScala)
                        .getOrThrow.filter(_.getId != old.getId)
                    ops = ops ++ synchronizeElementChain(store, serviceChain,
                                                         elementChain, elems)
                }
                catch {
                    case e: Exception =>
                        ignoreMissing match {
                            case true => return true
                            case false => return false
                        }
                }
            case _ =>
                return false
        }
        if (ops.size > 0)
            store.multi(ops)
        true
    }
}
