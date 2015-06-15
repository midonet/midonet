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

package org.midonet.cluster.data.storage

import java.util
import java.util.UUID
import java.util.concurrent.TimeUnit

import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

import org.scalatest.{BeforeAndAfter, FeatureSpec, Matchers}

import org.midonet.cluster.data.storage.FieldBinding.DeleteAction
import org.midonet.cluster.data.storage.FieldBinding.DeleteAction._
import org.midonet.cluster.data.storage.StorageTestClasses._
import org.midonet.cluster.models.Commons
import org.midonet.cluster.models.Topology._
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.cluster.util.{ClassAwaitableObserver, ParentDeletedException, UUIDUtil}
import org.midonet.util.reactivex.{AssertableObserver, TestAwaitableObserver}

abstract class StorageTest extends FeatureSpec with BeforeAndAfter
                           with Matchers {

    import StorageTest._

    protected var storage: StorageWithOwnership = _
    protected var assert: () => Unit = _

    protected def createStorage: StorageWithOwnership

    protected def initAndBuildStorage(storage: StorageWithOwnership): Unit = {
        List(classOf[PojoBridge], classOf[PojoRouter], classOf[PojoPort],
             classOf[PojoChain], classOf[PojoRule], classOf[Network],
             classOf[Router], classOf[Port], classOf[Chain],
             classOf[Rule]).foreach {
            clazz => storage.registerClass(clazz)
        }

        storage.registerClass(classOf[ExclusiveState], OwnershipType.Exclusive)
        storage.registerClass(classOf[SharedState], OwnershipType.Shared)

        storage.declareBinding(classOf[PojoBridge], "inChainId", CLEAR,
                               classOf[PojoChain], "bridgeIds", CLEAR)
        storage.declareBinding(classOf[PojoBridge], "outChainId", CLEAR,
                               classOf[PojoChain], "bridgeIds", CLEAR)

        storage.declareBinding(classOf[PojoRouter], "inChainId", CLEAR,
                               classOf[PojoChain], "routerIds", CLEAR)
        storage.declareBinding(classOf[PojoRouter], "outChainId", CLEAR,
                               classOf[PojoChain], "routerIds", CLEAR)

        storage.declareBinding(classOf[PojoPort], "bridgeId", CLEAR,
                               classOf[PojoBridge], "portIds", ERROR)
        storage.declareBinding(classOf[PojoPort], "routerId", CLEAR,
                               classOf[PojoRouter], "portIds", ERROR)
        storage.declareBinding(classOf[PojoPort], "inChainId", CLEAR,
                               classOf[PojoChain], "portIds", CLEAR)
        storage.declareBinding(classOf[PojoPort], "outChainId", CLEAR,
                               classOf[PojoChain], "portIds", CLEAR)
        storage.declareBinding(classOf[PojoPort], "peerId", CLEAR,
                               classOf[PojoPort], "peerId", CLEAR)

        storage.declareBinding(classOf[PojoChain], "ruleIds", CASCADE,
                               classOf[PojoRule], "chainId", CLEAR)

        storage.declareBinding(classOf[PojoRule], "portIds", CLEAR,
                               classOf[PojoPort], "ruleIds", CLEAR)

        storage.declareBinding(classOf[Network], "inbound_filter_id", CLEAR,
                               classOf[Chain], "network_ids", CLEAR)
        storage.declareBinding(classOf[Network], "outbound_filter_id", CLEAR,
                               classOf[Chain], "network_ids", CLEAR)

        storage.declareBinding(classOf[Router], "inbound_filter_id", CLEAR,
                               classOf[Chain], "router_ids", CLEAR)
        storage.declareBinding(classOf[Router], "outbound_filter_id", CLEAR,
                               classOf[Chain], "router_ids", CLEAR)

        storage.declareBinding(classOf[Port], "network_id", CLEAR,
                               classOf[Network], "port_ids", ERROR)
        storage.declareBinding(classOf[Port], "router_id", CLEAR,
                               classOf[Router], "port_ids", ERROR)
        storage.declareBinding(classOf[Port], "inbound_filter_id", CLEAR,
                               classOf[Chain], "port_ids", CLEAR)
        storage.declareBinding(classOf[Port], "outbound_filter_id", CLEAR,
                               classOf[Chain], "port_ids", CLEAR)
        storage.declareBinding(classOf[Port], "peer_id", CLEAR,
                               classOf[Port], "peer_id", CLEAR)

        storage.declareBinding(classOf[Chain], "rule_ids", CASCADE,
                               classOf[Rule], "chain_id", CLEAR)

        storage.declareBinding(classOf[Rule], "in_port_ids", CLEAR,
                               classOf[Port], "inbound_filter_id", CLEAR)
        storage.declareBinding(classOf[Rule], "out_port_ids", CLEAR,
                               classOf[Port], "outbound_filter_id", CLEAR)

        storage.build()
    }

    private def makeObservable[T](assertFunc: () => Unit = assert) =
        new TestAwaitableObserver[T] with AssertableObserver[T] {
            override def assert() = assertFunc()
        }

    feature("Test multi") {
        scenario("Test multi create Java") {
            val bridge = createPojoBridge()
            val port = createPojoPort(bridgeId = bridge.id)
            storage.multi(List(CreateOp(bridge), CreateOp(port)))

            val updatedBridge = await(storage.get(classOf[PojoBridge], bridge.id))
            updatedBridge.portIds.asScala should equal(List(port.id))
        }

        scenario("Test multi create Protocol Buffers") {
            val network = createProtoNetwork()
            val port = createProtoPort(networkId = network.getId)
            storage.multi(List(CreateOp(network), CreateOp(port)))

            val updatedNetwork = await(storage.get(classOf[Network], network.getId.asJava))
            updatedNetwork.getPortIdsList.asScala should contain only port.getId
        }

        scenario("Test multi create, update and delete Java") {
            val chain = createPojoChain(name = "chain1")
            storage.create(chain)

            val chain2 = createPojoChain(name = "chain2")
            val bridge = createPojoBridge(inChainId = chain.id)
            val bridgeUpdate = createPojoBridge(id = bridge.id,
                                                inChainId = chain.id,
                                                outChainId = chain2.id)
            val router = createPojoRouter(outChainId = chain.id)
            val routerUpdate = createPojoRouter(id = router.id,
                                                inChainId = chain2.id,
                                                outChainId = chain.id)
            storage.multi(List(CreateOp(chain2),
                               CreateOp(bridge),
                               CreateOp(router),
                               UpdateOp(bridgeUpdate),
                               UpdateOp(routerUpdate),
                               DeleteOp(classOf[PojoChain], chain.id)))

            val updatedChain2 = await(storage.get(classOf[PojoChain], chain2.id))
            updatedChain2.bridgeIds.asScala should equal(List(bridge.id))
            updatedChain2.routerIds.asScala should equal(List(router.id))

            val updatedBridge = await(storage.get(classOf[PojoBridge], bridge.id))
            updatedBridge.inChainId shouldBe null
            updatedBridge.outChainId should equal(chain2.id)

            val updatedRouter = await(storage.get(classOf[PojoRouter], router.id))
            updatedRouter.inChainId should equal(chain2.id)
            updatedRouter.outChainId shouldBe null

            await(storage.exists(classOf[PojoChain], chain.id)) shouldBe false
        }

        scenario("Test multi create, update and delete Protocol Buffers") {
            val chain = createProtoChain(name = "chain1")
            storage.create(chain)

            val chain2 = createProtoChain(name = "chain2")
            val network = createProtoNetwork(inChainId = chain.getId)
            val networkUpdate = createProtoNetwork(id = network.getId,
                                                   inChainId = chain.getId,
                                                   outChainId = chain2.getId)
            val router = createProtoRouter(outChainId = chain.getId)
            val routerUpdate = createProtoRouter(id = router.getId,
                                                 inChainId = chain2.getId,
                                                 outChainId = chain.getId)
            storage.multi(List(CreateOp(chain2),
                               CreateOp(network),
                               CreateOp(router),
                               UpdateOp(networkUpdate),
                               UpdateOp(routerUpdate),
                               DeleteOp(classOf[Chain], chain.getId)))

            val updatedChain2 = await(storage.get(classOf[Chain], chain2.getId.asJava))
            updatedChain2.getNetworkIdsList.asScala should contain only network.getId
            updatedChain2.getRouterIdsList.asScala should contain only router.getId

            val updatedNetwork = await(storage.get(classOf[Network], network.getId.asJava))
            updatedNetwork.hasInboundFilterId shouldBe false
            updatedNetwork.getOutboundFilterId shouldBe chain2.getId

            val updatedRouter = await(storage.get(classOf[Router], router.getId.asJava))
            updatedRouter.getInboundFilterId shouldBe chain2.getId
            updatedRouter.hasOutboundFilterId shouldBe false

            await(storage.exists(classOf[PojoChain], chain.getId.asJava)) shouldBe false
        }

        scenario("Test multi update and cascading delete Java") {
            val chain1 = createPojoChain(name = "chain1")
            val rule1 = createPojoRule(name = "rule1", chainId = chain1.id)
            val rule2 = createPojoRule(name = "rule2", chainId = chain1.id)
            val rule3 = createPojoRule(name = "rule3", chainId = chain1.id)
            storage.multi(List(CreateOp(chain1), CreateOp(rule1),
                               CreateOp(rule2), CreateOp(rule3)))

            val chain2 = createPojoChain(name = "chain2")
            rule3.chainId = chain2.id
            storage.multi(List(CreateOp(chain2), UpdateOp(rule3),
                               DeleteOp(classOf[PojoChain], chain1.id)))

            await(storage.exists(classOf[PojoChain], chain1.id)) shouldBe false
            await(storage.exists(classOf[PojoRule], rule1.id)) shouldBe false
            await(storage.exists(classOf[PojoRule], rule2.id)) shouldBe false

            val updatedChain2 = await(storage.get(classOf[PojoChain], chain2.id))
            updatedChain2.ruleIds.asScala should equal(List(rule3.id))

            val updatedRule3 = await(storage.get(classOf[PojoRule], rule3.id))
            updatedRule3.chainId should equal(chain2.id)
        }

        scenario("Test multi update and cascading delete Protocol Buffers") {
            val chain1 = createProtoChain(name = "chain1")
            val rule1 = createProtoRule(chainId = chain1.getId)
            val rule2 = createProtoRule(chainId = chain1.getId)
            val rule3 = createProtoRule(chainId = chain1.getId)
            storage.multi(List(CreateOp(chain1), CreateOp(rule1),
                               CreateOp(rule2), CreateOp(rule3)))

            val chain2 = createProtoChain(name = "chain2")
            val rule3Update = createProtoRule(id = rule3.getId, chainId = chain2.getId)
            storage.multi(List(CreateOp(chain2), UpdateOp(rule3Update),
                               DeleteOp(classOf[Chain], chain1.getId.asJava)))

            await(storage.exists(classOf[Chain], chain1.getId.asJava)) shouldBe false
            await(storage.exists(classOf[Rule], rule1.getId.asJava)) shouldBe false
            await(storage.exists(classOf[Rule], rule2.getId.asJava)) shouldBe false

            val updatedChain2 = await(storage.get(classOf[Chain], chain2.getId.asJava))
            updatedChain2.getRuleIdsList.asScala should contain only rule3.getId

            val updatedRule3 = await(storage.get(classOf[Rule], rule3.getId.asJava))
            updatedRule3.getChainId shouldBe chain2.getId
        }

        scenario("Test multi with update of deleted object Java") {
            val chain = createPojoChain()
            val rule = createPojoRule(chainId = chain.id)
            try {
                storage.multi(List(CreateOp(chain), CreateOp(rule),
                                   DeleteOp(classOf[PojoChain], chain.id),
                                   UpdateOp(rule)))
                fail("Rule update should fail due to rule being deleted by " +
                     "cascade from chain deletion.")
            } catch {
                case nfe: NotFoundException =>
                    nfe.clazz shouldBe classOf[PojoRule]
                    nfe.id should equal(rule.id)
            }
        }

        scenario("Test multi with update of deleted object Protocol Buffers") {
            val chain = createProtoChain()
            val rule = createProtoRule(chainId = chain.getId)
            try {
                storage.multi(List(CreateOp(chain), CreateOp(rule),
                                   DeleteOp(classOf[Chain], chain.getId.asJava),
                                   UpdateOp(rule)))
                fail("Rule update should fail due to rule being deleted by " +
                     "cascade from chain deletion.")
            } catch {
                case nfe: NotFoundException =>
                    nfe.clazz should be(classOf[Rule])
                    nfe.id shouldBe rule.getId
            }
        }

        scenario("Test multi with redundant delete Java") {
            val chain = createPojoChain()
            val rule = createPojoRule(chainId = chain.id)
            try {
                storage.multi(List(CreateOp(chain), CreateOp(rule),
                                   DeleteOp(classOf[PojoChain], chain.id),
                                   DeleteOp(classOf[PojoRule], rule.id)))
                fail("Rule deletion should fail due to rule being deleted by " +
                     "cascade from chain deletion.")
            } catch {
                case nfe: NotFoundException =>
                    nfe.clazz shouldBe classOf[PojoRule]
                    nfe.id should equal(rule.id)
            }
        }

        scenario("Test multi with redundant delete Protocol Buffers") {
            val chain = createProtoChain()
            val rule = createProtoRule(chainId = chain.getId)
            try {
                storage.multi(List(CreateOp(chain), CreateOp(rule),
                                   DeleteOp(classOf[Chain], chain.getId),
                                   DeleteOp(classOf[Rule], rule.getId)))
                fail("Rule deletion should fail due to rule being deleted by " +
                     "cascade from chain deletion.")
            } catch {
                case nfe: NotFoundException =>
                    nfe.clazz should be(classOf[Rule])
                    nfe.id shouldBe rule.getId
            }
        }

        scenario("Test multi ID get Java") {
            implicit val es = ExecutionContext.global
            val chains = List("chain0", "chain1", "chain2").map(createPojoChain)
            storage.multi(chains.map(CreateOp))
            val twoIds = chains.take(2).map(_.id).asJava
            val twoChains = await(
                storage.getAll(classOf[PojoChain], twoIds.asScala))
            twoChains.map(_.name) should equal(List("chain0", "chain1"))
        }

        scenario("Test multi ID get Protocol Buffers") {
            implicit val es = ExecutionContext.global
            val chains = List("chain0", "chain1", "chain2")
                .map(createProtoChain(UUID.randomUUID, _))
            storage.multi(chains.map(CreateOp))
            val twoIds = chains.take(2).map(_.getId.asJava).asJava
            val twoChains = await(storage.getAll(classOf[Chain], twoIds.asScala))
            twoChains.map(_.getName) should equal(List("chain0", "chain1"))
        }

        scenario("Test create and update Java") {
            val bridge = createPojoBridge()
            storage.create(bridge)
            storage.multi(List(UpdateOp(bridge), UpdateOp(bridge)))
        }

        scenario("Test create and update Protocol Buffers") {
            val network = createProtoNetwork()
            storage.create(network)
            storage.multi(List(UpdateOp(network), UpdateOp(network)))
        }

        scenario("Test delete if exists Java") {
            storage.deleteIfExists(classOf[PojoBridge], UUID.randomUUID)
        }

        scenario("Test delete if exists Protocol Buffers") {
            storage.deleteIfExists(classOf[Network], UUID.randomUUID)
        }

        scenario("Test delete if exists on deleted object Java") {
            val bridge = createPojoBridge()
            storage.create(bridge)
            storage.delete(classOf[PojoBridge], bridge.id)
            // Idempotent delete.
            storage.deleteIfExists(classOf[PojoBridge], bridge.id)
        }

        scenario("Test delete if exists on deleted object Protocol Buffers") {
            val network = createProtoNetwork()
            storage.create(network)
            storage.delete(classOf[Network], network.getId.asJava)
            // Idempotent delete.
            storage.deleteIfExists(classOf[Network], network.getId.asJava)
        }

        scenario("Test delete of exists on deleted multi Java") {
            val bridge = createPojoBridge()
            storage.create(bridge)
            storage.multi(List(DeleteOp(classOf[PojoBridge], bridge.id),
                               DeleteOp(classOf[PojoBridge], bridge.id, true)))
        }

        scenario("Test delete of exists on deleted multi Protocol Buffers") {
            val network = createProtoNetwork()
            storage.create(network)
            storage.multi(List(DeleteOp(classOf[Network], network.getId.asJava),
                               DeleteOp(classOf[Network], network.getId.asJava,
                                        true)))
        }

        scenario("Test multi with redundant delete if exists Java") {
            val chain = createPojoChain()
            val rule = createPojoRule(chainId = chain.id)
            // The following two multis cannot be turned into a single multi.
            // Apparently it is a current limitation of ZOOM that in a single multi
            // one cannot delete an object that's just been created due to a race
            // to the backend ZooKeeper.
            storage.multi(List(CreateOp(chain), CreateOp(rule)))
            storage.multi(List(DeleteOp(classOf[PojoChain], chain.id),
                               DeleteOp(classOf[PojoRule], rule.id, true)))
        }

        scenario("Test multi with redundant delete if exists Protocol Buffers") {
            val chain = createProtoChain()
            val rule = createProtoRule(chainId = chain.getId.asJava)
            // The following two multis cannot be turned into a single multi.
            // Apparently it is a current limitation of ZOOM that in a single multi
            // one cannot delete an object that's just been created due to a race
            // to the backend ZooKeeper.
            storage.multi(List(CreateOp(chain), CreateOp(rule)))
            storage.multi(List(DeleteOp(classOf[Chain], chain.getId.asJava),
                               DeleteOp(classOf[Rule], rule.getId.asJava, true)))
        }
    }

    feature("Test subscribe") {
        scenario("Test subscribe") {
            val bridge = createPojoBridge()
            storage.create(bridge)
            val obs = makeObservable[PojoBridge]()
            storage.observable(classOf[PojoBridge], bridge.id).subscribe(obs)
            val port = createPojoPort(bridgeId = bridge.id)
            storage.create(port)

            obs.awaitOnNext(1, 1 second) shouldBe true
        }

        scenario("Test subscribe all") {
            storage.create(createPojoBridge())
            storage.create(createPojoBridge())

            val obs = new ClassAwaitableObserver[PojoBridge](2)
            storage.observable(classOf[PojoBridge]).subscribe(obs)
            obs.await(1 second, 0) shouldBe true
        }
    }

    feature("Test topologies") {
        scenario("Test ORM") {
            // Create two chains.
            val chain1= new PojoChain("chain1")
            val chain2 = new PojoChain("chain2")
            storage.create(chain1)
            storage.create(chain2)

            // Add bridge referencing the two chains.
            val bridge = new StorageTestClasses.PojoBridge(
                "bridge1", chain1.id, chain2.id)
            storage.create(bridge)

            // Chains should have backrefs to the bridge.
            await(storage.get(classOf[PojoChain], chain1.id))
                .bridgeIds should contain (bridge.id)
            await(storage.get(classOf[PojoChain], chain2.id))
                .bridgeIds should contain (bridge.id)

            // Add a router referencing chain1 twice.
            val router = new StorageTestClasses.PojoRouter(
                "router1", chain1.id, chain1.id)
            storage.create(router)

            // Chain1 should have two references to the router.
            await(storage.get(classOf[PojoChain], chain1.id))
                .routerIds should contain theSameElementsAs Vector(router.id,
                                                                   router.id)

            // Add two ports each to bridge and router, linking two of them.
            val bPort1 = new PojoPort("bridge-port1", bridge.id, null)
            val bPort2 = new PojoPort("bridge-port2", bridge.id, null)
            val rPort1 = new PojoPort("router-port1", null, router.id)
            val rPort2 = new PojoPort("router-port2", null, router.id,
                                      bPort2.id, null, null)
            storage.create(bPort1)
            storage.create(bPort2)
            storage.create(rPort1)
            storage.create(rPort2)

            // The ports' IDs should show up in their parents' portIds lists,
            // and bPort2 should have its peerId set.
            await(storage.get(classOf[PojoBridge], bridge.id))
                .portIds should contain allOf(bPort1.id, bPort2.id)
            await(storage.get(classOf[PojoRouter], router.id))
                .portIds should contain allOf(rPort1.id, rPort2.id)
            await(storage.get(classOf[PojoPort], bPort2.id))
                .peerId shouldBe rPort2.id

            // Should not be able to link bPort1 to rPort2 because rPort2 is
            // already linked.
            bPort1.peerId = rPort2.id
            intercept[ReferenceConflictException] {
                storage.update(bPort1)
            }

            // Link bPort1 and rPort1 with an update.
            bPort1.peerId = rPort1.id
            storage.update(bPort1)
            await(storage.get(classOf[PojoPort], rPort1.id))
                .peerId shouldBe bPort1.id

            // Add some rules to the chains.
            val c1Rule1 = new PojoRule("chain1-rule1",
                                       chain1.id, bPort1.id, bPort2.id)
            val c1Rule2 = new PojoRule("chain1-rule2",
                                       chain1.id, bPort2.id, rPort1.id)
            val c2Rule1 = new PojoRule("chain2-rule1",
                                       chain2.id, rPort1.id, bPort1.id)
            storage.create(c1Rule1)
            storage.create(c1Rule2)
            storage.create(c2Rule1)

            await(storage.get(classOf[PojoChain], chain1.id))
                .ruleIds should contain allOf(c1Rule1.id, c1Rule2.id)
            await(storage.get(classOf[PojoChain], chain2.id))
                .ruleIds should contain (c2Rule1.id)

            assertPortsRuleIds(bPort1, c1Rule1.id,
                               c2Rule1.id)
            assertPortsRuleIds(bPort2, c1Rule1.id,
                               c1Rule2.id)
            assertPortsRuleIds(rPort1, c1Rule2.id,
                               c2Rule1.id)

            // Try some updates on c2Rule1's ports.
            c2Rule1.portIds = util.Arrays.asList(bPort2.id, rPort1.id, rPort2.id)
            storage.update(c2Rule1)
            assertPortsRuleIds(bPort1, c1Rule1.id)
            assertPortsRuleIds(bPort2, c1Rule1.id, c1Rule2.id, c2Rule1.id)
            assertPortsRuleIds(rPort1, c1Rule2.id, c2Rule1.id)
            assertPortsRuleIds(rPort2, c2Rule1.id)

            c2Rule1.portIds = util.Arrays.asList(rPort1.id, bPort1.id)
            storage.update(c2Rule1)
            assertPortsRuleIds(bPort1, c1Rule1.id, c2Rule1.id)
            assertPortsRuleIds(bPort2, c1Rule1.id, c1Rule2.id)
            assertPortsRuleIds(rPort1, c1Rule2.id, c2Rule1.id)
            await(storage.get(classOf[PojoPort], rPort2.id))
                .ruleIds.isEmpty shouldBe true

            // Should not be able to delete the bridge while it has ports.
            intercept[ObjectReferencedException] {
                storage.delete(classOf[PojoBridge], bridge.id)
            }

            // Delete a bridge port and verify that references to it are cleared.
            storage.delete(classOf[PojoPort], bPort1.id)
            await(storage.exists(classOf[PojoPort], bPort1.id)) shouldBe false
            await(storage.get(classOf[PojoBridge], bridge.id))
                .portIds should contain (bPort2.id)
            await(storage.get(classOf[PojoRule], c1Rule1.id))
                .portIds should contain (bPort2.id)
            await(storage.get(classOf[PojoRule], c2Rule1.id))
                .portIds should contain (rPort1.id)

            // Delete the other bridge port.
            storage.delete(classOf[PojoPort], bPort2.id)
            await(storage.exists(classOf[PojoPort], bPort2.id)) shouldBe false
            await(storage.get(classOf[PojoBridge], bridge.id))
                .portIds.isEmpty shouldBe true
            await(storage.get(classOf[PojoPort], rPort2.id))
                .peerId shouldBe null
            await(storage.get(classOf[PojoRule], c1Rule1.id))
                .portIds.isEmpty shouldBe true
            await(storage.get(classOf[PojoRule], c1Rule2.id))
                .portIds should contain (rPort1.id)

            // Delete the bridge and verify references to it are cleared.
            storage.delete(classOf[PojoBridge], bridge.id)
            await(storage.get(classOf[PojoChain], chain1.id))
                .bridgeIds.isEmpty shouldBe true
            await(storage.get(classOf[PojoChain], chain2.id))
                .bridgeIds.isEmpty shouldBe true

            // Delete a chain and verify that the delete cascades to rules.
            storage.delete(classOf[PojoChain], chain1.id)
            await(storage.exists(classOf[PojoChain], chain1.id)) shouldBe false
            await(storage.exists(classOf[PojoRule], c1Rule1.id)) shouldBe false
            await(storage.exists(classOf[PojoRule], c1Rule2.id)) shouldBe false

            // Additionally, the cascading delete of c1Rule2 should have cleared
            // rPort1's reference to it.
            assertPortsRuleIds(rPort1, c2Rule1.id)
        }
    }

    feature("Test register") {
        scenario("Test register class with no ID field") {
            val st = createStorage
            intercept[IllegalArgumentException] {
                st.registerClass(classOf[NoIdField])
            }
        }

        scenario("Test register duplicate class") {
            val st = createStorage
            st.registerClass(classOf[PojoBridge])
            intercept[IllegalStateException] {
                st.registerClass(classOf[PojoBridge])
            }
        }
    }

    feature("Test bindings") {
        scenario("Test bind unregistered class") {
            val st = createStorage
            st.registerClass(classOf[Router])
            intercept[AssertionError] {
                st.declareBinding(
                    classOf[Router], "inbound_filter_id", DeleteAction.CLEAR,
                    classOf[Chain], "router_ids", DeleteAction.CLEAR)
            }
        }

        scenario("Test bind Java class to Protocol Buffers") {
            val st = createStorage
            st.registerClass(classOf[PojoBridge])
            st.registerClass(classOf[Chain])
            intercept[IllegalArgumentException] {
                st.declareBinding(
                    classOf[PojoBridge], "inChainId", DeleteAction.CLEAR,
                    classOf[Chain], "bridge_ids", DeleteAction.CLEAR)
            }
        }

        scenario("Test cascade to delete error") {
            val st = createStorage
            st.registerClass(classOf[PojoBridge])
            st.registerClass(classOf[PojoChain])
            st.registerClass(classOf[PojoRule])
            st.declareBinding(
                classOf[PojoBridge], "inChainId", DeleteAction.CASCADE,
                classOf[PojoChain], "bridgeIds", DeleteAction.CLEAR)
            st.declareBinding(
                classOf[PojoChain], "ruleIds", DeleteAction.ERROR,
                classOf[PojoRule], "chainId", DeleteAction.CLEAR)
            st.build()

            val chain = new PojoChain("chain")
            val rule = new PojoRule("rule", chain.id)
            val bridge = new PojoBridge("bridge", chain.id, null)
            st.create(chain)
            st.create(rule)
            st.create(bridge)

            intercept[ObjectReferencedException] {
                st.delete(classOf[PojoBridge], bridge.id)
            }
        }
    }

    feature("Test CRUD operations") {
        scenario("Test get non-existing object") {
            val id = UUID.randomUUID
            val e = intercept[NotFoundException] {
                await(storage.get(classOf[PojoBridge], id))
            }
            e.clazz shouldBe classOf[PojoBridge]
            e.id shouldBe id
        }

        scenario("Test create for unregistered class") {
            intercept[AssertionError] {
                storage.create(LoadBalancer.getDefaultInstance)
            }
        }

        scenario("Test create with existing ID") {
            val chain = new PojoChain("chain")
            storage.create(chain)
            val e = intercept[ObjectExistsException] {
                storage.create(chain)
            }
            e.clazz shouldBe classOf[PojoChain]
            e.id shouldBe chain.id.toString
        }

        scenario("Test create with missing reference") {
            val rule = new PojoRule("rule", UUID.randomUUID)
            intercept[NotFoundException] {
                storage.create(rule)
            }
        }

        scenario("Test create proto network") {
            val netIn = createProtoNetwork()
            storage.create(netIn)

            val netOut = await(storage.get(classOf[Network], netIn.getId))
            netIn shouldBe netOut
        }

        scenario("Test create proto network with existing ID") {
            val network = createProtoNetwork()
            storage.create(network)

            val e = intercept[ObjectExistsException] {
                storage.create(network)
            }
            e.clazz shouldBe classOf[Network]
            e.id shouldBe network.getId.asJava.toString
        }

        scenario("Test create proto network with in chains") {
            var chainIn = createProtoChain()
            storage.create(chainIn)

            // Add a network referencing an in-bound chain.
            val netIn = createProtoNetwork(inChainId = chainIn.getId)
            storage.create(netIn)

            val netOut = await(storage.get(classOf[Network], netIn.getId))
            netOut shouldBe netIn

            // Chains should have backrefs to the network.
            chainIn = await(storage.get(classOf[Chain], chainIn.getId))
            chainIn.getNetworkIdsList should contain (netIn.getId)
        }

        scenario("Test update for unregistered class") {
            intercept[AssertionError] {
                storage.update(LoadBalancer.getDefaultInstance)
            }
        }

        scenario("Test update proto network") {
            val netIn = createProtoNetwork()
            storage.create(netIn)

            // Changes the tunnel key value.
            val updatedNetwork = Network.newBuilder(netIn)
                .setTunnelKey(20)
                .build()
            storage.update(updatedNetwork)

            val netOut = await(storage.get(classOf[Network], netIn.getId))
            netOut shouldBe updatedNetwork
        }

        scenario("Test update proto network with in chains") {
            val network = createProtoNetwork()
            storage.create(network)

            val inChain = createProtoChain()
            storage.create(inChain)

            // Wait for the chain to be created.
            await(storage.get(classOf[Chain], inChain.getId))

            // Update the network with an in-bound chain.
            val updatedNetwork = network.toBuilder
                .setInboundFilterId(inChain.getId).build
            storage.update(updatedNetwork)

            val networkOut = await(storage.get(classOf[Network], network.getId))
            networkOut.getInboundFilterId shouldBe inChain.getId

            // Chains should have back refs to the network.
            val in = await(storage.get(classOf[Chain], inChain.getId))
            in.getNetworkIdsList should contain (network.getId)
        }

        scenario("Test update with non-existing ID") {
            val chain = createPojoChain("chain")
            val e = intercept[NotFoundException] {
                storage.update(chain)
            }
            e.clazz shouldBe classOf[PojoChain]
            e.id shouldBe chain.id
        }

        scenario("Test update proto network with non-existing ID") {
            val network = createProtoNetwork()
            val e = intercept[NotFoundException] {
                storage.update(network)
            }
            e.clazz shouldBe classOf[Network]
            e.id shouldBe network.getId
        }

        scenario("Test update with missing reference") {
            val rule = createPojoRule("rule")
            storage.create(rule)

            rule.chainId = UUID.randomUUID
            val e = intercept[NotFoundException] {
                storage.update(rule)
            }
            e.clazz shouldBe classOf[PojoChain]
            e.id shouldBe rule.chainId
        }

        scenario("Test update with reference conflict") {
            val rule = createPojoRule("rule")
            storage.create(rule)

            val chain1 = createPojoChain("chain1")
            chain1.ruleIds = util.Arrays.asList(rule.id)
            storage.create(chain1)

            val chain2 = createPojoChain("chain2")
            storage.create(chain2)

            chain2.ruleIds = util.Arrays.asList(rule.id)
            val e = intercept[ReferenceConflictException] {
                storage.update(chain2)
            }
            e.referencingClass shouldBe classOf[PojoRule].getSimpleName
            e.referencingFieldName shouldBe "chainId"
            e.referencedClass shouldBe classOf[PojoChain].getSimpleName
            e.referencedId shouldBe chain1.id.toString
        }

        scenario("Test update with validator error") {
            val rule = createPojoRule("rule")
            storage.create(rule)

            rule.name = "updates"
            intercept[IllegalStateException] {
                storage.update(rule, new UpdateValidator[PojoRule] {
                    override def validate(oldObj: PojoRule,
                                          newObj: PojoRule): PojoRule = {
                        if (oldObj.name ne newObj.name)
                            throw new IllegalStateException("Expected")
                        newObj
                    }
                })
            }
        }

        scenario("Test update with validator modification") {
            val rule = createPojoRule("rule")
            storage.create(rule)

            // Wait for the rule to be created.
            await(storage.get(classOf[PojoRule], rule.id))

            storage.update(rule, new UpdateValidator[PojoRule] {
                override def validate(oldObj: PojoRule,
                                      newObj: PojoRule): PojoRule = {
                    newObj.name = "renamed"
                    null
                }
            })

            val renamed = await(storage.get(classOf[PojoRule], rule.id))
            renamed.name shouldBe "renamed"
        }

        scenario("Test update with validator returning modified object") {
            val rule = createPojoRule("rule")
            storage.create(rule)

            // Wait for the rule to be created.
            await(storage.get(classOf[PojoRule], rule.id))

            storage.update(rule, new UpdateValidator[PojoRule] {
                override def validate(oldObj: PojoRule,
                                      newObj: PojoRule): PojoRule = {
                    val replacement = createPojoRule("replacement")
                    replacement.id = rule.id
                    replacement
                }
            })

            val replacement = await(storage.get(classOf[PojoRule], rule.id))
            replacement.name shouldBe "replacement"
        }

        scenario("Test update with validator modifying ID") {
            val rule = createPojoRule("rule")
            storage.create(rule)

            // Wait for the rule to be created.
            await(storage.get(classOf[PojoRule], rule.id))

            intercept[IllegalArgumentException] {
                storage.update(rule, new UpdateValidator[PojoRule] {
                    override def validate(oldObj: PojoRule,
                                          newObj: PojoRule): PojoRule = {
                        createPojoRule("rule")
                    }
                })
            }
        }

        scenario("Test delete for unregistered class") {
            intercept[AssertionError] {
                storage.delete(classOf[LoadBalancer], UUID.randomUUID)
            }
        }

        scenario("Test delete proto network") {
            val network = createProtoNetwork()
            storage.create(network)
            storage.delete(classOf[Network], network.getId)

            val e = intercept[NotFoundException] {
                await(storage.get(classOf[Network], network.getId))
            }
            e.clazz shouldBe classOf[Network]
            e.id shouldBe network.getId
        }

        scenario("Test delete proto network with in chain") {
            val inChain = createProtoChain()
            storage.create(inChain)

            // Add a network referencing an in-bound chain.
            val network = createProtoNetwork(inChainId = inChain.getId)
            storage.create(network)

            storage.delete(classOf[Network], network.getId)

            // Get on the network should throw a NotFoundException.
            val e = intercept[NotFoundException] {
                await(storage.get(classOf[Network], network.getId))
            }
            e.clazz shouldBe classOf[Network]
            e.id shouldBe network.getId

            // Chains should not have the backrefs to the network.
            val in = await(storage.get(classOf[Chain], inChain.getId))
            in.getNetworkIdsList.isEmpty shouldBe true
        }

        scenario("Test delete non-existing object") {
            val id = UUID.randomUUID
            val e = intercept[NotFoundException] {
                storage.delete(classOf[PojoBridge], id)
            }
            e.clazz shouldBe classOf[PojoBridge]
            e.id shouldBe id
        }

        scenario("Test get all with empty result") {
            await(storage.getAll(classOf[PojoChain])).isEmpty shouldBe true
        }

        scenario("Test get all with multiple objects") {
            val chain1 = createPojoChain()
            val chain2 = createPojoChain()
            storage.create(chain1)
            await(storage.getAll(classOf[PojoChain]))
                .map(_.id) should contain theSameElementsAs Vector(chain1.id)
            storage.create(chain2)
            await(storage.getAll(classOf[PojoChain]))
                .map(_.id) should contain theSameElementsAs Vector(chain1.id,
                                                                   chain2.id)
        }

        scenario("Test subscriber gets initial value") {
            val chain = createPojoChain()
            storage.create(chain)


            val obs = makeObservable[PojoChain]()
            storage.observable(classOf[PojoChain], chain.id).subscribe(obs)
            obs.awaitOnNext(1, 1 second) shouldBe true
            obs.getOnNextEvents should have size 1
            obs.getOnNextEvents.get(0).id shouldBe chain.id
            obs.getOnErrorEvents shouldBe empty
            obs.getOnCompletedEvents shouldBe empty
        }

        scenario("Test subscriber gets updates") {
            val chain = createPojoChain()
            storage.create(chain)

            val obs = makeObservable[PojoChain]()
            storage.observable(classOf[PojoChain], chain.id).subscribe(obs)
            obs.awaitOnNext(1, 1 second) shouldBe true
            chain.name = "renamed_chain"
            storage.update(chain)
            obs.awaitOnNext(2, 1 second) shouldBe true
            obs.getOnNextEvents should have size 2
            obs.getOnNextEvents.get(1).name shouldBe chain.name
        }

        scenario("Test subscriber gets delete") {
            val chain = createPojoChain()
            storage.create(chain)

            val obs = makeObservable[PojoChain]()
            storage.observable(classOf[PojoChain], chain.id).subscribe(obs)
            obs.awaitOnNext(1, 1 second) shouldBe true
            obs.getOnNextEvents should have size 1 // the initial value
            storage.delete(classOf[PojoChain], chain.id)
            obs.awaitCompletion(1 second)
            obs.getOnNextEvents should have size 1
            obs.getOnCompletedEvents should have size 1
            obs.getOnErrorEvents shouldBe empty
        }

        scenario("Test subscribe to non-existent object") {
            val obs = makeObservable[PojoChain]()
            val id = UUID.randomUUID
            storage.observable(classOf[PojoChain], id).subscribe(obs)
            obs.awaitCompletion(1 second)
            val e = obs.getOnErrorEvents.get(0).asInstanceOf[NotFoundException]
            e.clazz shouldBe classOf[PojoChain]
            e.id.toString shouldBe id.toString
            obs.getOnCompletedEvents shouldBe empty
            obs.getOnErrorEvents should have size 1
        }

        scenario("Test second subscriber gets latest version") {
            val chain = createPojoChain()
            storage.create(chain)

            val obs1 = makeObservable[PojoChain]()
            storage.observable(classOf[PojoChain], chain.id).subscribe(obs1)
            obs1.awaitOnNext(1, 1 second) shouldBe true

            chain.name = "renamed_chain"
            storage.update(chain)
            obs1.awaitOnNext(2, 1 second) shouldBe true

            val obs2 = makeObservable[PojoChain]()
            storage.observable(classOf[PojoChain], chain.id).subscribe(obs2)
            obs2.awaitOnNext(1, 1 second) shouldBe true

            obs2.getOnNextEvents should have size 1
            obs2.getOnNextEvents.get(0).name shouldBe "renamed_chain"

            obs2.getOnCompletedEvents shouldBe empty
            obs2.getOnErrorEvents shouldBe empty
        }

        scenario("Test class subscriber gets current list") {
            val chain1 = createPojoChain()
            val chain2 = createPojoChain()
            storage.create(chain1)
            storage.create(chain2)

            val obs = new ClassAwaitableObserver[PojoChain](2)
            storage.observable(classOf[PojoChain]).subscribe(obs)
            obs.await(1 second, 0) shouldBe true
            obs.observers should have size 2
        }

        scenario("Test class subscriber gets new object") {
            val chain1 = createPojoChain(name = "chain1")
            storage.create(chain1)

            val obs = new ClassAwaitableObserver[PojoChain](1)
            storage.observable(classOf[PojoChain]).subscribe(obs)
            obs.await(1 second, 1) shouldBe true
            obs.observers should have size 1
            obs.observers.get(0).get.awaitOnNext(1, 1 second)
            obs.observers.get(0).get.getOnNextEvents.get(0).name shouldBe "chain1"

            val chain2 = createPojoChain(name = "chain2")
            storage.create(chain2)
            obs.await(1 second, 0) shouldBe true
            obs.observers should have size 2
        }

        scenario("Test second class subscriber gets current list") {
            val chain1 = createPojoChain()
            storage.create(chain1)

            val obs1 = new ClassAwaitableObserver[PojoChain](1)
            storage.observable(classOf[PojoChain]).subscribe(obs1)
            obs1.await(1 second, 1) shouldBe true
            obs1.observers should have size 1

            val chain2 = createPojoChain()
            storage.create(chain2)
            obs1.await(1 second, 0) shouldBe true

            val obs2 = new ClassAwaitableObserver[PojoChain](2)
            storage.observable(classOf[PojoChain]).subscribe(obs2)
            obs2.await(1 second, 0) shouldBe true
            obs2.observers should have size 2
        }

        scenario("Test class observable ignores deleted instances") {
            val chain1 = createPojoChain(name = "chain1")
            val chain2 = createPojoChain(name = "chain2")

            val obs1 = new ClassAwaitableObserver[PojoChain](2)
            storage.observable(classOf[PojoChain]).subscribe(obs1)

            storage.create(chain1)
            storage.create(chain2)

            obs1.await(1 second, 0) shouldBe true
            obs1.observers should have size 2

            val chain1Sub = obs1.observers.get(0).get
            chain1Sub.awaitOnNext(1, 1 second) shouldBe true

            storage.delete(classOf[PojoChain], chain1.id)
            chain1Sub.awaitCompletion(1 second)
            chain1Sub.getOnErrorEvents shouldBe empty
            chain1Sub.getOnCompletedEvents should have size 1
            // the initial value
            chain1Sub.getOnNextEvents.get(0).name shouldBe "chain1"

            // Subscribe to the deleted object
            val obs2 = new ClassAwaitableObserver[PojoChain](1)
            storage.observable(classOf[PojoChain]).subscribe(obs2)

            obs2.await(1 second, 0) shouldBe true
            obs2.observers.size shouldBe 1
            val chain2Sub = obs2.observers.get(0).get

            chain2Sub.awaitOnNext(1, 1 second) shouldBe true
            chain2Sub.getOnNextEvents.get(0).name shouldBe "chain2"
        }
    }

    def testUpdateOwnerExclusiveDifferentOwner(throwIfExists: Boolean): Unit = {
        val state = new ExclusiveState
        val owner1 = UUID.randomUUID
        val owner2 = UUID.randomUUID
        storage.create(state, owner1)
        await(storage.exists(classOf[ExclusiveState], state.id)) shouldBe true
        val e = intercept[OwnershipConflictException] {
            storage.updateOwner(classOf[ExclusiveState], state.id, owner2,
                                throwIfExists)
        }
        e.clazz shouldBe classOf[ExclusiveState].getSimpleName
        e.id shouldBe state.id.toString
        e.currentOwner shouldBe Set(owner1.toString)
        e.newOwner shouldBe owner2.toString
        await(storage.getOwners(classOf[ExclusiveState], state.id)) shouldBe Set(
            owner1.toString)
    }

    feature("Test ownership") {
        scenario("Test create exclusive owner") {
            val state = new ExclusiveState
            val owner = UUID.randomUUID
            storage.create(state, owner)
            await(storage.exists(classOf[ExclusiveState], state.id)) shouldBe true
            await(storage.getOwners(classOf[ExclusiveState], state.id)) shouldBe Set(
                owner.toString)
            /*
            val path = zom.getOwnerPath(classOf[ExclusiveState], state.id, owner)
            val stat = new Stat
            curator.getData.storingStatIn(stat).forPath(path)
            stat.getEphemeralOwner should not be 0L
            stat.getVersion shouldBe 0
             */
        }

        scenario("Test update exclusive new owner") {
            val state = new ExclusiveState
            storage.create(state)
            await(storage.exists(classOf[ExclusiveState], state.id)) shouldBe true

            val owner = UUID.randomUUID
            storage.update(state, owner, null)
            await(storage.getOwners(classOf[ExclusiveState], state.id)) shouldBe
                Set(owner.toString)
            /*
            val path = zom.getOwnerPath(classOf[ExclusiveState], state.id, owner)
            val stat = new Stat
            curator.getData.storingStatIn(stat).forPath(path)
            stat.getEphemeralOwner should not be 0L
            stat.getVersion shouldBe 0
             */
        }

        scenario("Test update exclusive same owner") {
            val state = new ExclusiveState
            val owner = UUID.randomUUID
            storage.create(state, owner)
            await(storage.exists(classOf[ExclusiveState], state.id)) shouldBe true
            await(storage.getOwners(classOf[ExclusiveState], state.id)) shouldBe Set(
                owner.toString)
            /*
            val path = zom.getOwnerPath(classOf[ExclusiveState], state.id, owner)
            val stat = new Stat
            curator.getData.storingStatIn(stat).forPath(path)
            stat.getEphemeralOwner should not be 0L
            stat.getVersion shouldBe 0
            val mzxid = stat.getMzxid
             */
            storage.update(state, owner, null)
            await(storage.getOwners(classOf[ExclusiveState], state.id)) shouldBe Set(
                owner.toString)
            /*
            curator.getData.storingStatIn(stat).forPath(path)
            stat.getEphemeralOwner should not be 0L
            stat.getVersion shouldBe 0
            stat.getMzxid should be > mzxid
             */
        }

        scenario("Test update exclusive different owner") {
            val state = new ExclusiveState
            val oldOwner = UUID.randomUUID
            val newOwner = UUID.randomUUID
            storage.create(state, oldOwner)
            await(storage.exists(classOf[ExclusiveState], state.id)) shouldBe true
            val e = intercept[OwnershipConflictException] {
                storage.update(state, newOwner, null)
            }
            await(storage.getOwners(classOf[ExclusiveState], state.id)) shouldBe Set(
                oldOwner.toString)
            e.clazz shouldBe classOf[ExclusiveState].getSimpleName
            e.id shouldBe state.id.toString
            e.currentOwner shouldBe Set(oldOwner.toString)
            e.newOwner shouldBe newOwner.toString
        }

        scenario("Test delete exclusive no owner") {
            val state = new ExclusiveState
            storage.create(state)
            await(storage.exists(classOf[ExclusiveState],
                                 state.id)) shouldBe true

            val owner = UUID.randomUUID
            val e = intercept[OwnershipConflictException] {
                storage.delete(classOf[ExclusiveState], state.id, owner)
            }
            e.clazz shouldBe classOf[ExclusiveState].getSimpleName
            e.id shouldBe state.id.toString
            e.currentOwner shouldBe Set()
            e.newOwner shouldBe owner.toString

            storage.delete(classOf[ExclusiveState], state.id)
            await(storage.exists(classOf[ExclusiveState],
                                 state.id)) shouldBe false
        }

        scenario("Test delete exclusive same owner") {
            val state = new ExclusiveState
            val owner = UUID.randomUUID
            storage.create(state, owner)
            await(storage.exists(classOf[ExclusiveState], state.id)) shouldBe true
            storage.delete(classOf[ExclusiveState], state.id, owner)
            await(storage.exists(classOf[ExclusiveState], state.id)) shouldBe false
        }

        scenario("Test delete exclusive different owner") {
            val state = new ExclusiveState
            val owner = UUID.randomUUID
            val otherOwner = UUID.randomUUID
            storage.create(state, owner)
            await(storage.exists(classOf[ExclusiveState], state.id)) shouldBe true
            val e = intercept[OwnershipConflictException] {
                storage.delete(classOf[ExclusiveState], state.id, otherOwner)
            }
            await(storage.exists(classOf[ExclusiveState], state.id)) shouldBe true
            e.clazz shouldBe classOf[ExclusiveState].getSimpleName
            e.id shouldBe state.id.toString
            e.currentOwner shouldBe Set(owner.toString)
            e.newOwner shouldBe otherOwner.toString
        }

        scenario("Test update owner exclusive new owner") {
            val state = new ExclusiveState
            storage.create(state)
            await(storage.exists(classOf[ExclusiveState],
                                 state.id)) shouldBe true

            val owner = UUID.randomUUID
            storage.updateOwner(classOf[ExclusiveState], state.id, owner, true)
            await(storage.getOwners(classOf[ExclusiveState], state.id)) shouldBe
                Set(owner.toString)
            /*
            val path = zom.getOwnerPath(classOf[ExclusiveState], state.id, owner)
            val stat = new Stat
            curator.getData.storingStatIn(stat).forPath(path)
            stat.getEphemeralOwner should not be 0L
            stat.getVersion shouldBe 0
             */
        }

        scenario("Test update owner exclusive same owner with throw if exists") {
            val state = new ExclusiveState
            val owner = UUID.randomUUID
            storage.create(state, owner)
            await(storage.exists(classOf[ExclusiveState], state.id)) shouldBe true
            val e = intercept[OwnershipConflictException] {
                storage.updateOwner(classOf[ExclusiveState], state.id, owner, true)
            }
            e.clazz shouldBe classOf[ExclusiveState].getSimpleName
            e.id shouldBe state.id.toString
            e.currentOwner shouldBe Set(owner.toString)
            e.newOwner shouldBe owner.toString
        }

        scenario("Test update owner exlcusive same owner no throw if exists") {
            val state = new ExclusiveState
            val owner = UUID.randomUUID
            storage.create(state, owner)
            await(storage.exists(classOf[ExclusiveState], state.id)) shouldBe true
            await(storage.getOwners(classOf[ExclusiveState], state.id)) shouldBe Set(
                owner.toString)
            /*
            val path = zom.getOwnerPath(classOf[ExclusiveState], state.id, owner)
            val stat = new Stat
            curator.getData.storingStatIn(stat).forPath(path)
            stat.getEphemeralOwner should not be 0L
            stat.getVersion shouldBe 0
            val mzxid = stat.getMzxid
             */
            storage.updateOwner(classOf[ExclusiveState], state.id, owner, false)
            await(storage.getOwners(classOf[ExclusiveState], state.id)) shouldBe Set(
                owner.toString)
            /*
            curator.getData.storingStatIn(stat).forPath(path)
            stat.getEphemeralOwner should not be 0L
            stat.getVersion shouldBe 0
            stat.getMzxid should be > mzxid
             */
        }

        scenario("Test update owner exclusive different owner with throw") {
            testUpdateOwnerExclusiveDifferentOwner(true)
        }

        scenario("Test update owner exclusive different owner no throw") {
            testUpdateOwnerExclusiveDifferentOwner(false)
        }

        scenario("Test create single shared owner") {
            val state = new SharedState
            val owner = UUID.randomUUID
            storage.create(state, owner)
            await(storage.exists(classOf[SharedState], state.id)) shouldBe true
            await(storage.getOwners(classOf[SharedState], state.id)) shouldBe Set(
                owner.toString)
            /*
            val path = zom.getOwnerPath(classOf[SharedState], state.id, owner)
            val stat = new Stat
            curator.getData.storingStatIn(stat).forPath(path)
            stat.getEphemeralOwner should not be 0L
            stat.getVersion shouldBe 0
             */
        }

        scenario("Test create multiple shared owners") {
            val state = new SharedState
            val owner1 = UUID.randomUUID
            val owner2 = UUID.randomUUID
            storage.create(state, owner1)
            await(storage.exists(classOf[SharedState], state.id)) shouldBe true
            await(storage.getOwners(classOf[SharedState], state.id)) shouldBe Set(
                owner1.toString)
            storage.update(state, owner2, null)
            await(storage.getOwners(classOf[SharedState], state.id)) shouldBe Set(
                owner1.toString, owner2.toString)
            /*
            val path1 = zom.getOwnerPath(classOf[SharedState], state.id, owner1)
            val path2 = zom.getOwnerPath(classOf[SharedState], state.id, owner2)
            val stat = new Stat
            curator.getData.storingStatIn(stat).forPath(path1)
            stat.getEphemeralOwner should not be 0L
            stat.getVersion shouldBe 0
            val mzxid = stat.getMzxid
            curator.getData.storingStatIn(stat).forPath(path2)
            stat.getEphemeralOwner should not be 0L
            stat.getVersion shouldBe 0
            stat.getMzxid should be > mzxid
             */
        }

        scenario("Test multiple create fails for shared owners") {
            val state = new SharedState
            val owner1 = UUID.randomUUID
            val owner2 = UUID.randomUUID
            storage.create(state, owner1)
            await(storage.exists(classOf[SharedState], state.id)) shouldBe true
            val e = intercept[ObjectExistsException] {
                storage.create(state, owner2)
            }
            e.clazz shouldBe classOf[SharedState]
            e.id shouldBe state.id.toString
        }

        scenario("Test update shared existing owner") {
            val state = new SharedState
            val owner = UUID.randomUUID
            storage.create(state, owner)
            await(storage.getOwners(classOf[SharedState], state.id)) shouldBe Set(
                owner.toString)
            /*
            val path = zom.getOwnerPath(classOf[SharedState], state.id, owner)
            val stat = new Stat
            curator.getData.storingStatIn(stat).forPath(path)
            stat.getEphemeralOwner should not be 0L
            stat.getVersion shouldBe 0
            val mzxid = stat.getMzxid
             */
            storage.update(state, owner, null)
            await(storage.getOwners(classOf[SharedState], state.id)) shouldBe Set(
                owner.toString)
            /*
            curator.getData.storingStatIn(stat).forPath(path)
            stat.getEphemeralOwner should not be 0L
            stat.getVersion shouldBe 0
            stat.getMzxid should be > mzxid
             */
        }

        scenario("Test update shared non-existing owner") {
            val state = new SharedState
            val owner1 = UUID.randomUUID
            val owner2 = UUID.randomUUID
            storage.create(state, owner1)
            await(storage.getOwners(classOf[SharedState], state.id)) shouldBe Set(
                owner1.toString)
            storage.update(state, owner2, null)
            await(storage.getOwners(classOf[SharedState], state.id)) shouldBe Set(
                owner1.toString, owner2.toString)
            storage.update(state, owner2, null)
            await(storage.getOwners(classOf[SharedState], state.id)) shouldBe Set(
                owner1.toString, owner2.toString)
        }

        scenario("Test update shared existing single owner") {
            val state = new SharedState
            val owner = UUID.randomUUID
            storage.create(state, owner)
            await(storage.exists(classOf[SharedState], state.id)) shouldBe true
            await(storage.getOwners(classOf[SharedState], state.id)) shouldBe Set(
                owner.toString)
            storage.delete(classOf[SharedState], state.id, owner)
            await(storage.exists(classOf[SharedState], state.id)) shouldBe false
        }

        scenario("Test delete shared existing multiple owner") {
            val state = new SharedState
            val owner1 = UUID.randomUUID
            val owner2 = UUID.randomUUID
            storage.create(state, owner1)
            storage.update(state, owner2, null)
            await(storage.exists(classOf[SharedState], state.id)) shouldBe true
            await(storage.getOwners(classOf[SharedState], state.id)) shouldBe Set(
                owner1.toString, owner2.toString)
            storage.delete(classOf[SharedState], state.id, owner1)
            await(storage.exists(classOf[SharedState], state.id)) shouldBe true
            await(storage.getOwners(classOf[SharedState], state.id)) shouldBe Set(
                owner2.toString)
            storage.delete(classOf[SharedState], state.id, owner2)
            await(storage.exists(classOf[SharedState], state.id)) shouldBe false
        }

        scenario("Test delete shared non-existing owner") {
            val state = new SharedState
            val owner = UUID.randomUUID
            val otherOwner = UUID.randomUUID
            storage.create(state, owner)
            await(storage.exists(classOf[SharedState], state.id)) shouldBe true
            await(storage.getOwners(classOf[SharedState], state.id)) shouldBe Set(
                owner.toString)
            val e = intercept[OwnershipConflictException] {
                storage.delete(classOf[SharedState], state.id, otherOwner)
            }
            await(storage.exists(classOf[SharedState], state.id)) shouldBe true
            await(storage.getOwners(classOf[SharedState], state.id)) shouldBe Set(
                owner.toString)
            e.clazz shouldBe classOf[SharedState].getSimpleName
            e.id shouldBe state.id.toString
            e.currentOwner shouldBe Set(owner.toString)
            e.newOwner shouldBe otherOwner.toString
        }

        scenario("Test shared ownership lifecycle") {
            val state = new SharedState
            val owner1 = UUID.randomUUID
            val owner2 = UUID.randomUUID
            val owner3 = UUID.randomUUID
            val owner4 = UUID.randomUUID
            storage.create(state, owner1)
            await(storage.exists(classOf[SharedState], state.id)) shouldBe true
            await(storage.getOwners(classOf[SharedState], state.id)) shouldBe Set(
                owner1.toString)
            storage.update(state, owner2, null)
            await(storage.getOwners(classOf[SharedState], state.id)) shouldBe Set(
                owner1.toString, owner2.toString)
            storage.update(state, owner3, null)
            await(storage.getOwners(classOf[SharedState], state.id)) shouldBe Set(
                owner1.toString, owner2.toString, owner3.toString)
            storage.delete(classOf[SharedState], state.id, owner1)
            await(storage.exists(classOf[SharedState], state.id)) shouldBe true
            await(storage.getOwners(classOf[SharedState], state.id)) shouldBe Set(
                owner2.toString, owner3.toString)
            storage.update(state, owner4, null)
            await(storage.getOwners(classOf[SharedState], state.id)) shouldBe Set(
                owner2.toString, owner3.toString, owner4.toString)
            storage.delete(classOf[SharedState], state.id, owner2)
            await(storage.exists(classOf[SharedState], state.id)) shouldBe true
            await(storage.getOwners(classOf[SharedState], state.id)) shouldBe Set(
                owner3.toString, owner4.toString)
            storage.delete(classOf[SharedState], state.id, owner3)
            await(storage.exists(classOf[SharedState], state.id)) shouldBe true
            await(storage.getOwners(classOf[SharedState], state.id)) shouldBe Set(
                owner4.toString)
            storage.delete(classOf[SharedState], state.id, owner4)
            await(storage.exists(classOf[SharedState], state.id)) shouldBe false
            val e = intercept[NotFoundException] {
                await(storage.getOwners(classOf[SharedState], state.id))
            }
            e.clazz shouldBe classOf[SharedState]
            e.id shouldBe state.id
        }

        scenario("Test update owner shared same owner with throw if exists") {
            val state = new SharedState
            val owner = UUID.randomUUID
            storage.create(state, owner)
            await(storage.exists(classOf[SharedState], state.id)) shouldBe true
            val e = intercept[OwnershipConflictException] {
                storage.updateOwner(classOf[SharedState], state.id, owner, true)
            }
            e.clazz shouldBe classOf[SharedState].getSimpleName
            e.id shouldBe state.id.toString
            e.currentOwner shouldBe Set(owner.toString)
            e.newOwner shouldBe owner.toString
        }

        scenario("Test update owner shared same owner no throw if exists") {
            val state = new SharedState
            val owner = UUID.randomUUID
            storage.create(state, owner)
            await(storage.exists(classOf[SharedState], state.id)) shouldBe true
            await(storage.getOwners(classOf[SharedState], state.id)) shouldBe Set(
                owner.toString)
            /*
            val path = zom.getOwnerPath(classOf[SharedState], state.id, owner)
            val stat = new Stat
            curator.getData.storingStatIn(stat).forPath(path)
            stat.getEphemeralOwner should not be 0L
            stat.getVersion shouldBe 0
            val mzxid = stat.getMzxid
             */
            storage.updateOwner(classOf[SharedState], state.id, owner, false)
            await(storage.getOwners(classOf[SharedState], state.id)) shouldBe Set(
                owner.toString)
            /*
            curator.getData.storingStatIn(stat).forPath(path)
            stat.getEphemeralOwner should not be 0L
            stat.getVersion shouldBe 0
            stat.getMzxid should be > mzxid
             */
        }

        scenario("Test update owner shared different owner no rewrite") {
            val state = new SharedState
            val owner1 = UUID.randomUUID
            val owner2 = UUID.randomUUID
            storage.create(state, owner1)
            await(storage.exists(classOf[SharedState], state.id)) shouldBe true
            storage.updateOwner(classOf[SharedState], state.id, owner2, true)
            await(storage.getOwners(classOf[SharedState], state.id)) shouldBe Set(
                owner1.toString, owner2.toString)
        }

        scenario("Test update owner shared different owner with rewrite") {
            val state = new SharedState
            val owner1 = UUID.randomUUID
            val owner2 = UUID.randomUUID
            storage.create(state, owner1)
            await(storage.exists(classOf[SharedState], state.id)) shouldBe true
            storage.updateOwner(classOf[SharedState], state.id, owner2, false)
            await(storage.getOwners(classOf[SharedState], state.id)) shouldBe Set(
                owner1.toString, owner2.toString)
        }

        scenario("Test delete owner exclusive no owner") {
            val state = new ExclusiveState
            storage.create(state)
            await(storage.exists(classOf[ExclusiveState],
                                 state.id)) shouldBe true

            val owner = UUID.randomUUID
            val e = intercept[OwnershipConflictException] {
                storage.deleteOwner(classOf[ExclusiveState], state.id, owner)
            }
            e.clazz shouldBe classOf[ExclusiveState].getSimpleName
            e.id shouldBe state.id.toString
            e.currentOwner shouldBe Set()
            e.newOwner shouldBe owner.toString
        }

        scenario("Test delete owner exclusive same owner") {
            val state = new ExclusiveState
            val owner = UUID.randomUUID
            storage.create(state, owner)
            await(storage.exists(classOf[ExclusiveState], state.id)) shouldBe true
            storage.deleteOwner(classOf[ExclusiveState], state.id, owner)
            await(storage.exists(classOf[ExclusiveState], state.id)) shouldBe true
            await(storage.getOwners(classOf[ExclusiveState], state.id)) shouldBe empty
        }

        scenario("Test delete owner exclusive different owner") {
            val state = new ExclusiveState
            val owner = UUID.randomUUID
            val otherOwner = UUID.randomUUID
            storage.create(state, owner)
            await(storage.exists(classOf[ExclusiveState], state.id)) shouldBe true
            val e = intercept[OwnershipConflictException] {
                storage.deleteOwner(classOf[ExclusiveState], state.id, otherOwner)
            }
            e.clazz shouldBe classOf[ExclusiveState].getSimpleName
            e.id shouldBe state.id.toString
            e.currentOwner shouldBe Set(owner.toString)
            e.newOwner shouldBe otherOwner.toString
            await(storage.exists(classOf[ExclusiveState], state.id)) shouldBe true
            await(storage.getOwners(classOf[ExclusiveState], state.id)) shouldBe Set(
                owner.toString)
        }

        scenario("Test delete owner shared single owner") {
            val state = new SharedState
            val owner = UUID.randomUUID
            storage.create(state, owner)
            await(storage.exists(classOf[SharedState], state.id)) shouldBe true
            storage.deleteOwner(classOf[SharedState], state.id, owner)
            await(storage.exists(classOf[SharedState], state.id)) shouldBe true
            await(storage.getOwners(classOf[SharedState], state.id)) shouldBe Set.empty
        }

        scenario("Test delete owner shared single different owner") {
            val state = new SharedState
            val owner = UUID.randomUUID
            val otherOwner = UUID.randomUUID
            storage.create(state, owner)
            await(storage.exists(classOf[SharedState], state.id)) shouldBe true
            val e = intercept[OwnershipConflictException] {
                storage.deleteOwner(classOf[SharedState], state.id, otherOwner)
            }
            e.clazz shouldBe classOf[SharedState].getSimpleName
            e.id shouldBe state.id.toString
            e.currentOwner shouldBe Set(owner.toString)
            e.newOwner shouldBe otherOwner.toString
            await(storage.exists(classOf[SharedState], state.id)) shouldBe true
            await(storage.getOwners(classOf[SharedState], state.id)) shouldBe Set(
                owner.toString)
        }

        scenario("Test delete owner multiple existing owner") {
            val state = new SharedState
            val owner1 = UUID.randomUUID
            val owner2 = UUID.randomUUID
            storage.create(state, owner1)
            await(storage.exists(classOf[SharedState], state.id)) shouldBe true
            await(storage.getOwners(classOf[SharedState], state.id)) shouldBe Set(
                owner1.toString)
            storage.updateOwner(classOf[SharedState], state.id, owner2, false)
            await(storage.getOwners(classOf[SharedState], state.id)) shouldBe Set(
                owner1.toString, owner2.toString)
            storage.deleteOwner(classOf[SharedState], state.id, owner1)
            await(storage.getOwners(classOf[SharedState], state.id)) shouldBe Set(
                owner2.toString)
            storage.deleteOwner(classOf[SharedState], state.id, owner2)
            await(storage.getOwners(classOf[SharedState], state.id)) shouldBe Set.empty
        }

        scenario("Test delete owner multiple non-existing owner") {
            val state = new SharedState
            val owner1 = UUID.randomUUID
            val owner2 = UUID.randomUUID
            val otherOwner = UUID.randomUUID
            storage.create(state, owner1)
            await(storage.exists(classOf[SharedState], state.id)) shouldBe true
            await(storage.getOwners(classOf[SharedState], state.id)) shouldBe Set(
                owner1.toString)
            storage.updateOwner(classOf[SharedState], state.id, owner2, false)
            await(storage.getOwners(classOf[SharedState], state.id)) shouldBe Set(
                owner1.toString, owner2.toString)
            val e = intercept[OwnershipConflictException] {
                storage.deleteOwner(classOf[SharedState], state.id, otherOwner)
            }
            await(storage.getOwners(classOf[SharedState], state.id)) shouldBe Set(
                owner1.toString, owner2.toString)
        }

        /* Tests that we can create an exclusive-ownership object without
         * specifying an owner. */
        scenario("Test regular create on exclusive ownership type") {
            val state = new ExclusiveState
            storage.create(state)
            await(storage.exists(classOf[ExclusiveState],
                                 state.id)) shouldBe true
            await(storage.getOwners(classOf[ExclusiveState],
                                    state.id)) shouldBe empty
        }

        scenario("Test regular create on shared ownership type") {
            val state = new SharedState
            storage.create(state)
            await(storage.exists(classOf[SharedState], state.id)) shouldBe true
            await(storage.getOwners(classOf[SharedState], state.id)) shouldBe empty
        }

        /* Tests that we can perform an owner-less/agnostic update on an
         * exclusive -ownership object. */
        scenario("Test regular update on exclusive ownership type") {
            val state = new ExclusiveState
            val owner = UUID.randomUUID
            storage.create(state, owner)
            await(storage.exists(classOf[ExclusiveState], state.id)) shouldBe true

            storage.update(state)
            /*
            val path = zom.getOwnerPath(classOf[ExclusiveState], state.id, owner)
            val stat = new Stat
            curator.getData.storingStatIn(stat).forPath(path)
            stat.getEphemeralOwner should not be 0L
            stat.getVersion shouldBe 0
             */
        }

        scenario("Test regular update on shared ownership type") {
            val state = new SharedState
            val stateUpdate = new SharedState(state.id, 1)
            val owner = UUID.randomUUID
            storage.create(state, owner)
            await(storage.exists(classOf[SharedState], state.id)) shouldBe true
            await(storage.getOwners(classOf[SharedState], state.id)) shouldBe Set(
                owner.toString)
            storage.update(stateUpdate)
            await(storage.get(classOf[SharedState], state.id)).value shouldBe 1
            await(storage.getOwners(classOf[SharedState], state.id)) shouldBe Set(
                owner.toString)
        }

        scenario("Test regular delete on exclusive ownership type no owner") {
            val state = new ExclusiveState
            storage.create(state)
            await(storage.exists(classOf[ExclusiveState], state.id)) shouldBe true
            storage.delete(classOf[ExclusiveState], state.id)
            await(storage.exists(classOf[ExclusiveState], state.id)) shouldBe false
        }

        scenario("Test regular delete on exclusive ownership type") {
            val state = new ExclusiveState
            val owner = UUID.randomUUID
            storage.create(state, owner)
            await(storage.exists(classOf[ExclusiveState], state.id)) shouldBe true
            intercept[UnsupportedOperationException] {
                storage.delete(classOf[ExclusiveState], state.id)
            }
        }

        scenario("Test regular delete on shared ownership type for single owner") {
            val state = new SharedState
            val owner = UUID.randomUUID
            storage.create(state, owner)
            await(storage.exists(classOf[SharedState], state.id)) shouldBe true
            await(storage.getOwners(classOf[SharedState], state.id)) shouldBe Set(
                owner.toString)
            storage.delete(classOf[SharedState], state.id)
            await(storage.exists(classOf[SharedState], state.id)) shouldBe false
            intercept[NotFoundException] {
                await(storage.getOwners(classOf[SharedState], state.id))
            }
        }

        scenario("Test regular delete on shared ownership type for multiple owners") {
            val state = new SharedState
            val owner1 = UUID.randomUUID
            val owner2 = UUID.randomUUID
            storage.create(state, owner1)
            await(storage.exists(classOf[SharedState], state.id)) shouldBe true
            await(storage.getOwners(classOf[SharedState], state.id)) shouldBe Set(
                owner1.toString)
            storage.update(state, owner2, null)
            await(storage.getOwners(classOf[SharedState], state.id)) shouldBe Set(
                owner1.toString, owner2.toString)
            storage.delete(classOf[SharedState], state.id)
            await(storage.exists(classOf[SharedState], state.id)) shouldBe false
            intercept[NotFoundException] {
                await(storage.getOwners(classOf[SharedState], state.id))
            }
        }

        scenario("Test subscribe exclusive ownership on create") {
            val state = new ExclusiveState
            val owner = UUID.randomUUID
            val obs = makeObservable[Set[String]](assert)
            storage.create(state, owner)
            storage.ownersObservable(classOf[ExclusiveState], state.id).subscribe(obs)
            obs.awaitOnNext(1, 1 second) shouldBe true
            obs.getOnNextEvents should contain only Set(owner.toString)
        }

        scenario("Test subscribe exclusive ownership on update") {
            val state = new ExclusiveState
            val owner = UUID.randomUUID
            val obs = makeObservable[Set[String]](assert)
            storage.create(state, owner)
            storage.ownersObservable(classOf[ExclusiveState], state.id).subscribe(obs)
            obs.awaitOnNext(1, 1 second) shouldBe true
            storage.update(state, owner, null)
            obs.awaitOnNext(2, 1 second) shouldBe true
            obs.getOnNextEvents should contain theSameElementsAs Vector(
                Set(owner.toString), Set(owner.toString))
        }

        scenario("Test subscribe exclusive ownership on delete") {
            val state = new ExclusiveState
            val owner = UUID.randomUUID
            val obs = makeObservable[Set[String]](assert)
            storage.create(state, owner)
            storage.ownersObservable(classOf[ExclusiveState], state.id).subscribe(obs)
            obs.awaitOnNext(1, 1 second) shouldBe true
            storage.delete(classOf[ExclusiveState], state.id, owner)
            obs.awaitCompletion(1 second)
            obs.getOnNextEvents should contain only Set(owner.toString)
            obs.getOnErrorEvents.get(0).getClass shouldBe classOf[
                ParentDeletedException]
        }

        scenario("Test subscribe exclusive non-existing object") {
            val owner = UUID.randomUUID
            val id = UUID.randomUUID
            val obs = makeObservable[Set[String]](assert)
            storage.ownersObservable(classOf[ExclusiveState], id).subscribe(obs)
            obs.awaitCompletion(1 second)
            obs.getOnNextEvents shouldBe empty
            obs.getOnErrorEvents.get(0).getClass shouldBe classOf[
                ParentDeletedException]
        }

        scenario("Test subscribe shared ownership on create") {
            val state = new SharedState
            val owner = UUID.randomUUID
            val obs = makeObservable[Set[String]](assert)
            storage.create(state, owner)
            storage.ownersObservable(classOf[SharedState], state.id).subscribe(obs)
            obs.awaitOnNext(1, 1 second) shouldBe true
            obs.getOnNextEvents should contain only Set(owner.toString)
        }

        scenario("Test subscribe shared ownership single owner") {
            val state = new SharedState
            val owner = UUID.randomUUID
            val obs = makeObservable[Set[String]](assert)
            storage.create(state, owner)
            storage.ownersObservable(classOf[SharedState], state.id).subscribe(obs)
            obs.awaitOnNext(1, 1 second) shouldBe true
            storage.update(state, owner, null)
            obs.awaitOnNext(2, 1 second) shouldBe true
            storage.delete(classOf[SharedState], state.id, owner)
            obs.awaitCompletion(1 second)
            obs.getOnNextEvents should contain theSameElementsAs Vector(
                Set(owner.toString), Set(owner.toString))
            obs.getOnErrorEvents.get(0).getClass shouldBe classOf[
                ParentDeletedException]
        }

        scenario("Test subscribe shared ownership multiple owners") {
            val state = new SharedState
            val owner1 = UUID.randomUUID
            val owner2 = UUID.randomUUID
            val owner3 = UUID.randomUUID
            val obs = makeObservable[Set[String]](assert)
            storage.create(state, owner1)
            storage.ownersObservable(classOf[SharedState], state.id).subscribe(obs)
            obs.awaitOnNext(1, 1 second) shouldBe true
            storage.update(state, owner2, null)
            obs.awaitOnNext(2, 1 second) shouldBe true
            storage.update(state, owner3, null)
            obs.awaitOnNext(3, 1 second) shouldBe true
            storage.delete(classOf[SharedState], state.id, owner1)
            obs.awaitOnNext(4, 1 second) shouldBe true
            storage.delete(classOf[SharedState], state.id, owner2)
            obs.awaitOnNext(5, 1 second) shouldBe true
            storage.delete(classOf[SharedState], state.id, owner3)
            obs.awaitCompletion(1 second)
            obs.getOnNextEvents should contain theSameElementsAs Vector(
                Set(owner1.toString),
                Set(owner1.toString, owner2.toString),
                Set(owner1.toString, owner2.toString, owner3.toString),
                Set(owner2.toString, owner3.toString),
                Set(owner3.toString))
            obs.getOnErrorEvents.get(0).getClass shouldBe classOf[
                ParentDeletedException]
        }

        scenario("Test subscribe owner update delete") {
            val state = new SharedState
            val owner1 = UUID.randomUUID
            val owner2 = UUID.randomUUID
            val owner3 = UUID.randomUUID
            val obs = makeObservable[Set[String]](assert)
            storage.create(state)
            storage.ownersObservable(classOf[SharedState], state.id).subscribe(obs)
            obs.awaitOnNext(1, 1 second) shouldBe true
            storage.updateOwner(classOf[SharedState], state.id, owner1, false)
            obs.awaitOnNext(2, 1 second) shouldBe true
            storage.updateOwner(classOf[SharedState], state.id, owner2, false)
            obs.awaitOnNext(3, 1 second) shouldBe true
            storage.updateOwner(classOf[SharedState], state.id, owner3, false)
            obs.awaitOnNext(4, 1 second) shouldBe true
            storage.deleteOwner(classOf[SharedState], state.id, owner1)
            obs.awaitOnNext(5, 1 second) shouldBe true
            storage.deleteOwner(classOf[SharedState], state.id, owner2)
            obs.awaitOnNext(6, 1 second) shouldBe true
            storage.deleteOwner(classOf[SharedState], state.id, owner3)
            await(storage.exists(classOf[SharedState], state.id)) shouldBe true
            obs.getOnNextEvents should contain theSameElementsAs Vector(
                Set.empty,
                Set(owner1.toString),
                Set(owner1.toString, owner2.toString),
                Set(owner1.toString, owner2.toString, owner3.toString),
                Set(owner2.toString, owner3.toString),
                Set(owner3.toString),
                Set.empty)
        }

        scenario("Test subscribe owner when performing a multi operation") {
            val state = new SharedState
            val owner = UUID.randomUUID.toString
            val obs = new TestAwaitableObserver[Set[String]]
            storage.create(state, owner)
            storage.ownersObservable(classOf[SharedState], state.id).subscribe(obs)
            obs.awaitOnNext(1, 1 second) shouldBe true
            obs.getOnNextEvents.get(0) should contain only owner
            storage.multi(Seq(DeleteWithOwnerOp(classOf[SharedState], state.id, owner),
                              CreateWithOwnerOp(state, owner),
                              DeleteWithOwnerOp(classOf[SharedState], state.id, owner),
                              CreateWithOwnerOp(state, owner)))
            obs.awaitOnNext(2, 1 second) shouldBe true
            obs.getOnNextEvents.get(1) should contain only owner
        }
    }

    @throws(classOf[Exception])
    private def assertPortsRuleIds(port: StorageTestClasses.PojoPort,
                                   ruleIds: UUID*) {
        await(storage.get(classOf[PojoPort], port.id))
            .ruleIds should contain theSameElementsAs ruleIds
    }

}

private object StorageTest {

    def createPojoBridge(id: UUID = UUID.randomUUID, name: String = null,
                         inChainId: UUID = null, outChainId: UUID = null) = {
        new PojoBridge(id, name, inChainId, outChainId)
    }

    def createPojoRouter(id: UUID = UUID.randomUUID, name: String = null,
                         inChainId: UUID = null, outChainId: UUID = null) = {
        new PojoRouter(id, name, inChainId, outChainId)
    }

    def createPojoPort(name: String = null, peerId: UUID = null,
                       bridgeId: UUID = null, routerId: UUID = null,
                       inChainId: UUID = null, outChainId: UUID = null) = {
        new PojoPort(name, bridgeId, routerId, peerId, inChainId, outChainId)
    }

    def createPojoChain(name: String = null) = {
        new PojoChain(name)
    }

    def createPojoRule(name: String = null, chainId: UUID = null,
                       portIds: List[UUID] = null) = {
        if (portIds == null) new PojoRule(name, chainId)
        else new PojoRule(name, chainId, portIds:_*)
    }

    def createProtoNetwork(id: UUID = UUID.randomUUID, name: String = null,
                           inChainId: UUID = null, outChainId: UUID = null)
    : Network = {
        val builder = Network.newBuilder.setId(id.asProto)
        if (name ne null) builder.setName(name)
        if (inChainId ne null) builder.setInboundFilterId(inChainId.asProto)
        if (outChainId ne null) builder.setOutboundFilterId(outChainId.asProto)
        builder.build()
    }

    def createProtoRouter(id: UUID = UUID.randomUUID, name: String = null,
                          inChainId: UUID = null, outChainId: UUID = null)
    : Router = {
        val builder = Router.newBuilder.setId(id.asProto)
        if (name ne null) builder.setName(name)
        if (inChainId ne null) builder.setInboundFilterId(inChainId.asProto)
        if (outChainId ne null) builder.setOutboundFilterId(outChainId.asProto)
        builder.build()
    }

    def createProtoPort(id: UUID = UUID.randomUUID, peerId: UUID = null,
                        networkId: UUID = null, routerId: UUID = null,
                        inChainId: UUID = null, outChainId: UUID = null)
    : Port = {
        val builder = Port.newBuilder.setId(id.asProto)
        if (peerId ne null) builder.setPeerId(peerId.asProto)
        if (networkId ne null) builder.setNetworkId(networkId.asProto)
        if (routerId ne null) builder.setRouterId(routerId.asProto)
        if (inChainId ne null) builder.setInboundFilterId(inChainId.asProto)
        if (outChainId ne null) builder.setOutboundFilterId(outChainId.asProto)
        builder.build()
    }

    def createProtoChain(chainId: Commons.UUID = UUIDUtil.randomUuidProto,
                         name: String = "chain") = {
        Chain.newBuilder
            .setId(chainId)
            .setName(name)
            .build()
    }

    def createProtoRule(id: UUID = UUID.randomUUID, chainId: UUID = null,
                        inPortIds: Seq[UUID] = null, outPortIds: Seq[UUID] = null)
    : Rule = {
        val builder = Rule.newBuilder.setId(id.asProto)
        if (chainId ne null) builder.setChainId(chainId.asProto)
        if (inPortIds ne null) builder.addAllInPortIds(inPortIds.map(_.asProto).asJava)
        if (outPortIds ne null) builder.addAllOutPortIds(outPortIds.map(_.asProto).asJava)
        builder.build()
    }

    def await[T](f: Future[T]) =
        Await.result(f, Duration.create(1, TimeUnit.SECONDS))

}