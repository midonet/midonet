/*
 * Copyright 2014 Midokura SARL
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

import java.util.UUID
import java.util.concurrent._

import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

import org.apache.zookeeper.data.Stat
import org.junit.Test
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{Matchers, Suite}

import rx.Observable

import org.midonet.cluster.data.storage.FieldBinding.DeleteAction._
import org.midonet.cluster.data.storage.ZookeeperObjectMapperTest._
import org.midonet.cluster.models.Topology._
import org.midonet.cluster.util.{ClassAwaitableObserver, CuratorTestFramework, ParentDeletedException, PathCacheDisconnectedException}
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.util.reactivex.AwaitableObserver

@RunWith(classOf[JUnitRunner])
class ZookeeperObjectMapperTests extends Suite
                                 with CuratorTestFramework
                                 with Matchers {
    import org.midonet.cluster.data.storage.ZookeeperObjectMapperTests._

    private var zom: ZookeeperObjectMapper = _

    override protected def setup(): Unit = {
        zom = new ZookeeperObjectMapper(ZK_ROOT, curator)
        initAndBuildZoom(zom)
    }

    private def initAndBuildZoom(zom: ZookeeperObjectMapper) {
        List(classOf[PojoBridge], classOf[PojoRouter], classOf[PojoPort],
             classOf[PojoChain], classOf[PojoRule], classOf[Network],
             classOf[Router], classOf[Port], classOf[Chain],
             classOf[Rule]).foreach {
            clazz => zom.registerClass(clazz)
        }

        zom.registerClass(classOf[ExclusiveState], OwnershipType.Exclusive)
        zom.registerClass(classOf[SharedState], OwnershipType.Shared)

        zom.declareBinding(classOf[PojoBridge], "inChainId", CLEAR,
                           classOf[PojoChain], "bridgeIds", CLEAR)
        zom.declareBinding(classOf[PojoBridge], "outChainId", CLEAR,
                           classOf[PojoChain], "bridgeIds", CLEAR)

        zom.declareBinding(classOf[PojoRouter], "inChainId", CLEAR,
                           classOf[PojoChain], "routerIds", CLEAR)
        zom.declareBinding(classOf[PojoRouter], "outChainId", CLEAR,
                           classOf[PojoChain], "routerIds", CLEAR)

        zom.declareBinding(classOf[PojoPort], "bridgeId", CLEAR,
                           classOf[PojoBridge], "portIds", ERROR)
        zom.declareBinding(classOf[PojoPort], "routerId", CLEAR,
                           classOf[PojoRouter], "portIds", ERROR)
        zom.declareBinding(classOf[PojoPort], "inChainId", CLEAR,
                           classOf[PojoChain], "portIds", CLEAR)
        zom.declareBinding(classOf[PojoPort], "outChainId", CLEAR,
                           classOf[PojoChain], "portIds", CLEAR)
        zom.declareBinding(classOf[PojoPort], "peerId", CLEAR,
                           classOf[PojoPort], "peerId", CLEAR)

        zom.declareBinding(classOf[PojoChain], "ruleIds", CASCADE,
                           classOf[PojoRule], "chainId", CLEAR)

        zom.declareBinding(classOf[PojoRule], "portIds", CLEAR,
                           classOf[PojoPort], "ruleIds", CLEAR)


        zom.declareBinding(classOf[Network], "inbound_filter_id", CLEAR,
                           classOf[Chain], "network_ids", CLEAR)
        zom.declareBinding(classOf[Network], "outbound_filter_id", CLEAR,
                           classOf[Chain], "network_ids", CLEAR)

        zom.declareBinding(classOf[Router], "inbound_filter_id", CLEAR,
                           classOf[Chain], "router_ids", CLEAR)
        zom.declareBinding(classOf[Router], "outbound_filter_id", CLEAR,
                           classOf[Chain], "router_ids", CLEAR)

        zom.declareBinding(classOf[Port], "network_id", CLEAR,
                           classOf[Network], "port_ids", ERROR)
        zom.declareBinding(classOf[Port], "router_id", CLEAR,
                           classOf[Router], "port_ids", ERROR)
        zom.declareBinding(classOf[Port], "inbound_filter_id", CLEAR,
                           classOf[Chain], "port_ids", CLEAR)
        zom.declareBinding(classOf[Port], "outbound_filter_id", CLEAR,
                           classOf[Chain], "port_ids", CLEAR)
        zom.declareBinding(classOf[Port], "peer_id", CLEAR,
                           classOf[Port], "peer_id", CLEAR)

        zom.declareBinding(classOf[Chain], "rule_ids", CASCADE,
                           classOf[Rule], "chain_id", CLEAR)

        zom.declareBinding(classOf[Rule], "in_port_ids", CLEAR,
                           classOf[Port], "inbound_filter_id", CLEAR)
        zom.declareBinding(classOf[Rule], "out_port_ids", CLEAR,
                           classOf[Port], "outbound_filter_id", CLEAR)

        zom.build()
    }

    private case class OwnerSnapshot(id: String, version: Int) {
        override def equals(obj: Any) = obj match {
            case os: OwnerSnapshot => id.equals(os.id)
            case _ => false
        }
        override def hashCode: Int = id.hashCode
    }

    def testMultiCreateJava() {
        val bridge = pojoBridge()
        val port = pojoPort(bridgeId = bridge.id)
        zom.multi(List(CreateOp(bridge), CreateOp(port)))

        val updatedBridge = await(zom.get(classOf[PojoBridge], bridge.id))
        updatedBridge.portIds.asScala should equal(List(port.id))
    }

    def testMultiCreateProto() {
        val network = protoNetwork()
        val port = protoPort(networkId = network.getId)
        zom.multi(List(CreateOp(network), CreateOp(port)))

        val updatedNetwork = await(zom.get(classOf[Network], network.getId.asJava))
        updatedNetwork.getPortIdsList.asScala should contain only port.getId
    }

    def testMultiCreateUpdateAndDeleteJava() {
        val chain = pojoChain(name = "chain1")
        zom.create(chain)

        val chain2 = pojoChain(name = "chain2")
        val bridge = pojoBridge(inChainId = chain.id)
        val bridgeUpdate = pojoBridge(id = bridge.id,
                                      inChainId = chain.id,
                                      outChainId = chain2.id)
        val router = pojoRouter(outChainId = chain.id)
        val routerUpdate = pojoRouter(id = router.id,
                                      inChainId = chain2.id,
                                      outChainId = chain.id)
        zom.multi(List(CreateOp(chain2),
                       CreateOp(bridge),
                       CreateOp(router),
                       UpdateOp(bridgeUpdate),
                       UpdateOp(routerUpdate),
                       DeleteOp(classOf[PojoChain], chain.id)))

        val updatedChain2 = await(zom.get(classOf[PojoChain], chain2.id))
        updatedChain2.bridgeIds.asScala should equal(List(bridge.id))
        updatedChain2.routerIds.asScala should equal(List(router.id))

        val updatedBridge = await(zom.get(classOf[PojoBridge], bridge.id))
        updatedBridge.inChainId shouldBe null
        updatedBridge.outChainId should equal(chain2.id)

        val updatedRouter = await(zom.get(classOf[PojoRouter], router.id))
        updatedRouter.inChainId should equal(chain2.id)
        updatedRouter.outChainId shouldBe null

        await(zom.exists(classOf[PojoChain], chain.id)) shouldBe false
    }

    def testMultiCreateUpdateAndDeleteProto() {
        val chain = protoChain(name = "chain1")
        zom.create(chain)

        val chain2 = protoChain(name = "chain2")
        val network = protoNetwork(inChainId = chain.getId)
        val networkUpdate = protoNetwork(id = network.getId,
                                         inChainId = chain.getId,
                                         outChainId = chain2.getId)
        val router = protoRouter(outChainId = chain.getId)
        val routerUpdate = protoRouter(id = router.getId,
                                       inChainId = chain2.getId,
                                       outChainId = chain.getId)
        zom.multi(List(CreateOp(chain2),
                       CreateOp(network),
                       CreateOp(router),
                       UpdateOp(networkUpdate),
                       UpdateOp(routerUpdate),
                       DeleteOp(classOf[Chain], chain.getId)))

        val updatedChain2 = await(zom.get(classOf[Chain], chain2.getId.asJava))
        updatedChain2.getNetworkIdsList.asScala should contain only network.getId
        updatedChain2.getRouterIdsList.asScala should contain only router.getId

        val updatedNetwork = await(zom.get(classOf[Network], network.getId.asJava))
        updatedNetwork.hasInboundFilterId shouldBe false
        updatedNetwork.getOutboundFilterId shouldBe chain2.getId

        val updatedRouter = await(zom.get(classOf[Router], router.getId.asJava))
        updatedRouter.getInboundFilterId shouldBe chain2.getId
        updatedRouter.hasOutboundFilterId shouldBe false

        await(zom.exists(classOf[PojoChain], chain.getId.asJava)) shouldBe false
    }

    def testMultiUpdateAndCascadingDeleteJava() {
        val chain1 = pojoChain(name = "chain1")
        val rule1 = pojoRule(name = "rule1", chainId = chain1.id)
        val rule2 = pojoRule(name = "rule2", chainId = chain1.id)
        val rule3 = pojoRule(name = "rule3", chainId = chain1.id)
        zom.multi(List(CreateOp(chain1), CreateOp(rule1),
                       CreateOp(rule2), CreateOp(rule3)))

        val chain2 = pojoChain(name = "chain2")
        rule3.chainId = chain2.id
        zom.multi(List(CreateOp(chain2), UpdateOp(rule3),
                       DeleteOp(classOf[PojoChain], chain1.id)))

        await(zom.exists(classOf[PojoChain], chain1.id)) shouldBe false
        await(zom.exists(classOf[PojoRule], rule1.id)) shouldBe false
        await(zom.exists(classOf[PojoRule], rule2.id)) shouldBe false

        val updatedChain2 = await(zom.get(classOf[PojoChain], chain2.id))
        updatedChain2.ruleIds.asScala should equal(List(rule3.id))

        val updatedRule3 = await(zom.get(classOf[PojoRule], rule3.id))
        updatedRule3.chainId should equal(chain2.id)
    }

    def testMultiUpdateAndCascadingDeleteProto() {
        val chain1 = protoChain(name = "chain1")
        val rule1 = protoRule(chainId = chain1.getId)
        val rule2 = protoRule(chainId = chain1.getId)
        val rule3 = protoRule(chainId = chain1.getId)
        zom.multi(List(CreateOp(chain1), CreateOp(rule1),
                       CreateOp(rule2), CreateOp(rule3)))

        val chain2 = protoChain(name = "chain2")
        val rule3Update = protoRule(id = rule3.getId, chainId = chain2.getId)
        zom.multi(List(CreateOp(chain2), UpdateOp(rule3Update),
                       DeleteOp(classOf[Chain], chain1.getId.asJava)))

        await(zom.exists(classOf[Chain], chain1.getId.asJava)) shouldBe false
        await(zom.exists(classOf[Rule], rule1.getId.asJava)) shouldBe false
        await(zom.exists(classOf[Rule], rule2.getId.asJava)) shouldBe false

        val updatedChain2 = await(zom.get(classOf[Chain], chain2.getId.asJava))
        updatedChain2.getRuleIdsList.asScala should contain only rule3.getId

        val updatedRule3 = await(zom.get(classOf[Rule], rule3.getId.asJava))
        updatedRule3.getChainId shouldBe chain2.getId
    }

    def testMultiWithUpdateOfDeletedObjectJava() {
        val chain = pojoChain()
        val rule = pojoRule(chainId = chain.id)
        try {
            zom.multi(List(CreateOp(chain), CreateOp(rule),
                           DeleteOp(classOf[PojoChain], chain.id),
                           UpdateOp(rule)))
            fail("Rule update should fail due to rule being deleted by " +
                 "cascade from chain deletion.")
        } catch {
            case nfe: NotFoundException =>
                nfe.clazz should be(classOf[PojoRule])
                nfe.id should equal(rule.id)
        }
    }

    def testMultiWithUpdateOfDeletedObjectProto() {
        val chain = protoChain()
        val rule = protoRule(chainId = chain.getId)
        try {
            zom.multi(List(CreateOp(chain), CreateOp(rule),
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

    def testMultiWithRedundantDeleteJava() {
        val chain = pojoChain()
        val rule = pojoRule(chainId = chain.id)
        try {
            zom.multi(List(CreateOp(chain), CreateOp(rule),
                           DeleteOp(classOf[PojoChain], chain.id),
                           DeleteOp(classOf[PojoRule], rule.id)))
            fail("Rule deletion should fail due to rule being deleted by " +
                 "cascade from chain deletion.")
        } catch {
            case nfe: NotFoundException =>
                nfe.clazz should be(classOf[PojoRule])
                nfe.id should equal(rule.id)
        }
    }

    def testMultiWithRedundantDeleteProto() {
        val chain = protoChain()
        val rule = protoRule(chainId = chain.getId)
        try {
            zom.multi(List(CreateOp(chain), CreateOp(rule),
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

    def testMultiIdGetJava() {
        implicit val es = ExecutionContext.global
        val chains = List("chain0", "chain1", "chain2").map(pojoChain)
        zom.multi(chains.map(CreateOp))
        val twoIds = chains.take(2).map(_.id).asJava
        val twoChains = await(
            Future.sequence(zom.getAll(classOf[PojoChain], twoIds.asScala))
        )
        twoChains.map(_.name) should equal(List("chain0", "chain1"))
    }

    def testMultiIdGetProto() {
        implicit val es = ExecutionContext.global
        val chains = List("chain0", "chain1", "chain2")
            .map(protoChain(UUID.randomUUID, _))
        zom.multi(chains.map(CreateOp))
        val twoIds = chains.take(2).map(_.getId.asJava).asJava
        val twoChains = await(
            Future.sequence(zom.getAll(classOf[Chain], twoIds.asScala))
        )
        twoChains.map(_.getName) should equal(List("chain0", "chain1"))
    }

    def testCreateAndUpdateJava(): Unit = {
        val bridge = pojoBridge()
        zom.create(bridge)
        zom.multi(List(UpdateOp(bridge), UpdateOp(bridge)))
    }

    def testCreateAndUpdateProto(): Unit = {
        val network = protoNetwork()
        zom.create(network)
        zom.multi(List(UpdateOp(network), UpdateOp(network)))
    }

    def testDeleteIfExistsJava() {
        zom.deleteIfExists(classOf[PojoBridge], UUID.randomUUID)
    }

    def testDeleteIfExistsProto() {
        zom.deleteIfExists(classOf[Network], UUID.randomUUID)
    }

    def testDeleteIfExistsOnDeletedObjectJava() {
        val bridge = pojoBridge()
        zom.create(bridge)
        zom.delete(classOf[PojoBridge], bridge.id)
        // Idempotent delete.
        zom.deleteIfExists(classOf[PojoBridge], bridge.id)
    }

    def testDeleteIfExistsOnDeletedObjectProto() {
        val network = protoNetwork()
        zom.create(network)
        zom.delete(classOf[Network], network.getId.asJava)
        // Idempotent delete.
        zom.deleteIfExists(classOf[Network], network.getId.asJava)
    }

    def testDeleteIfExistsOnDeletedObjectMultiJava() {
        val bridge = pojoBridge()
        zom.create(bridge)
        zom.multi(List(DeleteOp(classOf[PojoBridge], bridge.id),
                       DeleteOp(classOf[PojoBridge], bridge.id, true)))
    }

    def testDeleteIfExistsOnDeletedObjectMultiProto() {
        val network = protoNetwork()
        zom.create(network)
        zom.multi(List(DeleteOp(classOf[Network], network.getId.asJava),
                       DeleteOp(classOf[Network], network.getId.asJava, true)))
    }

    def testMultiWithRedundantDeleteIfExistsJava() {
        val chain = pojoChain()
        val rule = pojoRule(chainId = chain.id)
        // The following two multis cannot be turned into a single multi.
        // Apparently it is a current limitation of ZOOM that in a single multi
        // one cannot delete an object that's just been created due to a race
        // to the backend ZooKeeper.
        zom.multi(List(CreateOp(chain), CreateOp(rule)))
        zom.multi(List(DeleteOp(classOf[PojoChain], chain.id),
                       DeleteOp(classOf[PojoRule], rule.id, true)))
    }

    def testMultiWithRedundantDeleteIfExistsProto() {
        val chain = protoChain()
        val rule = protoRule(chainId = chain.getId.asJava)
        // The following two multis cannot be turned into a single multi.
        // Apparently it is a current limitation of ZOOM that in a single multi
        // one cannot delete an object that's just been created due to a race
        // to the backend ZooKeeper.
        zom.multi(List(CreateOp(chain), CreateOp(rule)))
        zom.multi(List(DeleteOp(classOf[Chain], chain.getId.asJava),
                       DeleteOp(classOf[Rule], rule.getId.asJava, true)))
    }

    private def createBridge() : PojoBridge = {
        val bridge = pojoBridge()
        zom.create(bridge)
        bridge
    }

    private def addPortToBridge(bId: UUID) = {
        val port = pojoPort(bridgeId = bId)
        zom.create(port)
    }

    @Test(timeout = 2000)
    def testSubscribe() {
        val bridge = createBridge()
        val obs = new AwaitableObserver[PojoBridge](1)
        zom.observable(classOf[PojoBridge], bridge.id).subscribe(obs)
        obs.await(1.second)
        obs.reset(1)
        addPortToBridge(bridge.id)
        obs.await(1.second)
    }

    def testSubscribeWithGc() = {
        val bridge = createBridge()
        val obs = new AwaitableObserver[PojoBridge](0)
        val sub = zom.observable(classOf[PojoBridge], bridge.id).subscribe(obs)

        zom.subscriptionCount(classOf[PojoBridge], bridge.id) shouldBe Option(1)
        sub.unsubscribe()
        zom.subscriptionCount(classOf[PojoBridge], bridge.id) shouldBe None
    }

    def testSubscribeAll() {
        createBridge()
        createBridge()

        val obs = new ClassAwaitableObserver[PojoBridge](2 /* We expect two events */)
        zom.observable(classOf[PojoBridge]).subscribe(obs)

        obs.await(1.second, 0)
    }

    def testSubscribeAllWithGc() {
        val obs = new ClassAwaitableObserver[PojoBridge](0)
        val sub = zom.observable(classOf[PojoBridge]).subscribe(obs)

        zom.subscriptionCount(classOf[PojoBridge]) should equal (Option(1))
        sub.unsubscribe()
        zom.subscriptionCount(classOf[PojoBridge]) should equal (None)

        obs.getOnCompletedEvents should have size 0
        obs.getOnErrorEvents should have size 1
        assert(obs.getOnErrorEvents.get(0)
                  .isInstanceOf[PathCacheDisconnectedException])
    }

    def testGetPath() {
        zom.getPath(classOf[PojoBridge]) should equal (s"$ZK_ROOT/1/PojoBridge")
    }

    def testVersionBump() {
        zom.getPath(classOf[PojoBridge]) should equal (s"$ZK_ROOT/1/PojoBridge")
        zom.flush()
        zom.getPath(classOf[PojoBridge]) should equal (s"$ZK_ROOT/2/PojoBridge")
    }

    def testZoomInheritsVersionNum() {
        zom.getPath(classOf[PojoBridge]) should equal (s"$ZK_ROOT/1/PojoBridge")
        zom.flush()

        val zom2 = new ZookeeperObjectMapper(ZK_ROOT, curator)
        initAndBuildZoom(zom2)
        zom2.getPath(classOf[PojoBridge]) should equal (s"$ZK_ROOT/2/PojoBridge")
    }

    def testZoomNotifiedVersionNumBump() {
        val zom2 = new ZookeeperObjectMapper(ZK_ROOT, curator)
        initAndBuildZoom(zom2)

        zom2.getPath(classOf[PojoBridge]) should equal (s"$ZK_ROOT/1/PojoBridge")

        zom.flush()

        zom2.getPath(classOf[PojoBridge]) should equal (s"$ZK_ROOT/2/PojoBridge")
    }

    def testFlushResetsWatcher() {
        zom.flush()
        val zom2 = new ZookeeperObjectMapper(ZK_ROOT, curator)
        initAndBuildZoom(zom2)

        zom2.flush()
        zom.getPath(classOf[PojoBridge]) should equal (s"$ZK_ROOT/3/PojoBridge")

        zom.flush()
        zom2.getPath(classOf[PojoBridge]) should equal (s"$ZK_ROOT/4/PojoBridge")
    }

    def testFlush() {
        val bridge = pojoBridge()
        val port = pojoPort(bridgeId = bridge.id)
        zom.multi(List(CreateOp(bridge), CreateOp(port)))
        await(zom.exists(classOf[PojoBridge], bridge.id)) should equal (true)
        await(zom.exists(classOf[PojoPort], port.id)) should equal (true)

        zom.flush()
        await(zom.exists(classOf[PojoBridge], bridge.id)) should equal (false)
        await(zom.exists(classOf[PojoPort], port.id)) should equal (false)

        // After flushing, ZOOM should be able to store new objects again.
        val bridge2 = pojoBridge()
        val port2 = pojoPort(bridgeId = bridge2.id)
        zom.multi(List(CreateOp(bridge2), CreateOp(port2)))
        await(zom.exists(classOf[PojoBridge], bridge2.id)) should equal (true)
        await(zom.exists(classOf[PojoPort], port2.id)) should equal (true)
    }

    def testFlushClosesSubscriptions() {
        val zom2 = new ZookeeperObjectMapper(ZK_ROOT, curator)
        initAndBuildZoom(zom2)

        val bridge = pojoBridge()
        val classObs1 = new AwaitableObserver[Observable[PojoBridge]]()
        val classObs2 = new AwaitableObserver[Observable[PojoBridge]]()
        zom.observable(classOf[PojoBridge]).subscribe(classObs1)
        zom2.observable(classOf[PojoBridge]).subscribe(classObs2)
        zom.multi(List(CreateOp(bridge)))

        classObs1.await(1.second, resetCount = 1)
        classObs1.getOnNextEvents should have size 1
        classObs1.getOnErrorEvents should have size 0
        classObs2.await(1.second, resetCount = 1)
        classObs2.getOnNextEvents should have size 1
        classObs2.getOnErrorEvents should have size 0

        val instanceObs1 = new AwaitableObserver[PojoBridge]()
        val instanceObs2 = new AwaitableObserver[PojoBridge]()
        zom.observable(classOf[PojoBridge], bridge.id).subscribe(instanceObs1)
        zom2.observable(classOf[PojoBridge], bridge.id).subscribe(instanceObs2)

        instanceObs1.await(1.second, resetCount = 1)
        instanceObs1.getOnNextEvents should have size 1
        instanceObs1.getOnErrorEvents should have size 0
        instanceObs2.await(1.second, resetCount = 1)
        instanceObs2.getOnNextEvents should have size 1
        instanceObs2.getOnErrorEvents should have size 0

        zom.flush()

        classObs1.await(1.second)
        classObs1.getOnNextEvents should have size 1
        classObs1.getOnErrorEvents should have size 1
        classObs2.await(1.second)
        classObs2.getOnNextEvents should have size 1
        classObs2.getOnErrorEvents should have size 1

        instanceObs1.await(1.second)
        instanceObs1.getOnNextEvents should have size 1
        instanceObs1.getOnErrorEvents should have size 1
        instanceObs2.await(1.second)
        instanceObs2.getOnNextEvents should have size 1
        instanceObs2.getOnErrorEvents should have size 1
    }

    def testCreateExclusiveOwner(): Unit = {
        val state = new ExclusiveState
        val owner = UUID.randomUUID
        val path = zom.getOwnerPath(classOf[ExclusiveState], state.id, owner)
        val stat = new Stat
        zom.create(state, owner)
        await(zom.exists(classOf[ExclusiveState], state.id)) shouldBe true
        await(zom.getOwners(classOf[ExclusiveState], state.id)) shouldBe Set(
            owner.toString)
        curator.getData.storingStatIn(stat).forPath(path)
        stat.getEphemeralOwner should not be 0L
        stat.getVersion shouldBe 0
    }

    def testUpdateExclusiveSameOwner(): Unit = {
        val state = new ExclusiveState
        val owner = UUID.randomUUID
        val path = zom.getOwnerPath(classOf[ExclusiveState], state.id, owner)
        val stat = new Stat
        zom.create(state, owner)
        await(zom.exists(classOf[ExclusiveState], state.id)) shouldBe true
        await(zom.getOwners(classOf[ExclusiveState], state.id)) shouldBe Set(
            owner.toString)
        curator.getData.storingStatIn(stat).forPath(path)
        stat.getEphemeralOwner should not be 0L
        stat.getVersion shouldBe 0
        val mzxid = stat.getMzxid
        zom.update(state, owner, null)
        await(zom.getOwners(classOf[ExclusiveState], state.id)) shouldBe Set(
            owner.toString)
        curator.getData.storingStatIn(stat).forPath(path)
        stat.getEphemeralOwner should not be 0L
        stat.getVersion shouldBe 0
        stat.getMzxid should be > mzxid
    }

    def testUpdateExclusiveDifferentOwner(): Unit = {
        val state = new ExclusiveState
        val oldOwner = UUID.randomUUID
        val newOwner = UUID.randomUUID
        zom.create(state, oldOwner)
        await(zom.exists(classOf[ExclusiveState], state.id)) shouldBe true
        val e = intercept[OwnershipConflictException] {
            zom.update(state, newOwner, null)
        }
        await(zom.getOwners(classOf[ExclusiveState], state.id)) shouldBe Set(
            oldOwner.toString)
        e.clazz shouldBe classOf[ExclusiveState].getSimpleName
        e.id shouldBe state.id.toString
        e.currentOwner shouldBe Set(oldOwner.toString)
        e.newOwner shouldBe newOwner.toString
    }

    def testDeleteExclusiveSameOwner(): Unit = {
        val state = new ExclusiveState
        val owner = UUID.randomUUID
        zom.create(state, owner)
        await(zom.exists(classOf[ExclusiveState], state.id)) shouldBe true
        zom.delete(classOf[ExclusiveState], state.id, owner)
        await(zom.exists(classOf[ExclusiveState], state.id)) shouldBe false
    }

    def testDeleteExclusiveDifferentOwner(): Unit = {
        val state = new ExclusiveState
        val owner = UUID.randomUUID
        val otherOwner = UUID.randomUUID
        zom.create(state, owner)
        await(zom.exists(classOf[ExclusiveState], state.id)) shouldBe true
        val e = intercept[OwnershipConflictException] {
            zom.delete(classOf[ExclusiveState], state.id, otherOwner)
        }
        await(zom.exists(classOf[ExclusiveState], state.id)) shouldBe true
        e.clazz shouldBe classOf[ExclusiveState].getSimpleName
        e.id shouldBe state.id.toString
        e.currentOwner shouldBe Set(owner.toString)
        e.newOwner shouldBe otherOwner.toString
    }

    def testUpdateOwnerExclusiveSameOwnerThrowIfExists(): Unit = {
        val state = new ExclusiveState
        val owner = UUID.randomUUID
        zom.create(state, owner)
        await(zom.exists(classOf[ExclusiveState], state.id)) shouldBe true
        val e = intercept[OwnershipConflictException] {
            zom.updateOwner(classOf[ExclusiveState], state.id, owner, true)
        }
        e.clazz shouldBe classOf[ExclusiveState].getSimpleName
        e.id shouldBe state.id.toString
        e.currentOwner shouldBe Set(owner.toString)
        e.newOwner shouldBe owner.toString
    }

    def testUpdateOwnerExclusiveSameOwnerNoThrowIfExists(): Unit = {
        val state = new ExclusiveState
        val owner = UUID.randomUUID
        val path = zom.getOwnerPath(classOf[ExclusiveState], state.id, owner)
        val stat = new Stat
        zom.create(state, owner)
        await(zom.exists(classOf[ExclusiveState], state.id)) shouldBe true
        await(zom.getOwners(classOf[ExclusiveState], state.id)) shouldBe Set(
            owner.toString)
        curator.getData.storingStatIn(stat).forPath(path)
        stat.getEphemeralOwner should not be 0L
        stat.getVersion shouldBe 0
        val mzxid = stat.getMzxid
        zom.updateOwner(classOf[ExclusiveState], state.id, owner, false)
        await(zom.getOwners(classOf[ExclusiveState], state.id)) shouldBe Set(
            owner.toString)
        curator.getData.storingStatIn(stat).forPath(path)
        stat.getEphemeralOwner should not be 0L
        stat.getVersion shouldBe 0
        stat.getMzxid should be > mzxid
    }

    def testUpdateOwnerExclusiveDifferentOwner(overwrite: Boolean): Unit = {
        val state = new ExclusiveState
        val owner1 = UUID.randomUUID
        val owner2 = UUID.randomUUID
        zom.create(state, owner1)
        await(zom.exists(classOf[ExclusiveState], state.id)) shouldBe true
        val e = intercept[OwnershipConflictException] {
            zom.updateOwner(classOf[ExclusiveState], state.id, owner2, overwrite)
        }
        e.clazz shouldBe classOf[ExclusiveState].getSimpleName
        e.id shouldBe state.id.toString
        e.currentOwner shouldBe Set(owner1.toString)
        e.newOwner shouldBe owner2.toString
        await(zom.getOwners(classOf[ExclusiveState], state.id)) shouldBe Set(
            owner1.toString)    }

    def testUpdateOwnerExclusiveDifferentOwnerNoOverwrite(): Unit = {
        testUpdateOwnerExclusiveDifferentOwner(false)
    }

    def testUpdateOwnerExclusiveDifferentOwnerWithOverwrite(): Unit = {
        testUpdateOwnerExclusiveDifferentOwner(true)
    }

    def testCreateSingleSharedOwner(): Unit = {
        val state = new SharedState
        val owner = UUID.randomUUID
        val path = zom.getOwnerPath(classOf[SharedState], state.id, owner)
        val stat = new Stat
        zom.create(state, owner)
        await(zom.exists(classOf[SharedState], state.id)) shouldBe true
        await(zom.getOwners(classOf[SharedState], state.id)) shouldBe Set(
            owner.toString)
        curator.getData.storingStatIn(stat).forPath(path)
        stat.getEphemeralOwner should not be 0L
        stat.getVersion shouldBe 0
    }

    def testCreateMultipleSharedOwners(): Unit = {
        val state = new SharedState
        val owner1 = UUID.randomUUID
        val owner2 = UUID.randomUUID
        val path1 = zom.getOwnerPath(classOf[SharedState], state.id, owner1)
        val path2 = zom.getOwnerPath(classOf[SharedState], state.id, owner2)
        val stat = new Stat
        zom.create(state, owner1)
        await(zom.exists(classOf[SharedState], state.id)) shouldBe true
        await(zom.getOwners(classOf[SharedState], state.id)) shouldBe Set(
            owner1.toString)
        zom.update(state, owner2, null)
        await(zom.getOwners(classOf[SharedState], state.id)) shouldBe Set(
            owner1.toString, owner2.toString)
        curator.getData.storingStatIn(stat).forPath(path1)
        stat.getEphemeralOwner should not be 0L
        stat.getVersion shouldBe 0
        val mzxid = stat.getMzxid
        curator.getData.storingStatIn(stat).forPath(path2)
        stat.getEphemeralOwner should not be 0L
        stat.getVersion shouldBe 0
        stat.getMzxid should be > mzxid
    }

    def testMultipleCreateFailsForSharedOwners(): Unit = {
        val state = new SharedState
        val owner1 = UUID.randomUUID
        val owner2 = UUID.randomUUID
        zom.create(state, owner1)
        await(zom.exists(classOf[SharedState], state.id)) shouldBe true
        val e = intercept[ObjectExistsException] {
            zom.create(state, owner2)
        }
        e.clazz shouldBe classOf[SharedState]
        e.id shouldBe state.id.toString
    }

    def testUpdateSharedExistingOwner(): Unit = {
        val state = new SharedState
        val owner = UUID.randomUUID
        val path = zom.getOwnerPath(classOf[SharedState], state.id, owner)
        val stat = new Stat
        zom.create(state, owner)
        await(zom.getOwners(classOf[SharedState], state.id)) shouldBe Set(
            owner.toString)
        curator.getData.storingStatIn(stat).forPath(path)
        stat.getEphemeralOwner should not be 0L
        stat.getVersion shouldBe 0
        val mzxid = stat.getMzxid
        zom.update(state, owner, null)
        await(zom.getOwners(classOf[SharedState], state.id)) shouldBe Set(
            owner.toString)
        curator.getData.storingStatIn(stat).forPath(path)
        stat.getEphemeralOwner should not be 0L
        stat.getVersion shouldBe 0
        stat.getMzxid should be > mzxid
    }

    def testUpdateSharedNonExistingOwner(): Unit = {
        val state = new SharedState
        val owner1 = UUID.randomUUID
        val owner2 = UUID.randomUUID
        zom.create(state, owner1)
        await(zom.getOwners(classOf[SharedState], state.id)) shouldBe Set(
            owner1.toString)
        zom.update(state, owner2, null)
        await(zom.getOwners(classOf[SharedState], state.id)) shouldBe Set(
            owner1.toString, owner2.toString)
        zom.update(state, owner2, null)
        await(zom.getOwners(classOf[SharedState], state.id)) shouldBe Set(
            owner1.toString, owner2.toString)
    }

    def testDeleteSharedExistingSingleOwner(): Unit = {
        val state = new SharedState
        val owner = UUID.randomUUID
        zom.create(state, owner)
        await(zom.exists(classOf[SharedState], state.id)) shouldBe true
        await(zom.getOwners(classOf[SharedState], state.id)) shouldBe Set(
            owner.toString)
        zom.delete(classOf[SharedState], state.id, owner)
        await(zom.exists(classOf[SharedState], state.id)) shouldBe false
    }

    def testDeleteSharedExistingMultipleOwner(): Unit = {
        val state = new SharedState
        val owner1 = UUID.randomUUID
        val owner2 = UUID.randomUUID
        zom.create(state, owner1)
        zom.update(state, owner2, null)
        await(zom.exists(classOf[SharedState], state.id)) shouldBe true
        await(zom.getOwners(classOf[SharedState], state.id)) shouldBe Set(
            owner1.toString, owner2.toString)
        zom.delete(classOf[SharedState], state.id, owner1)
        await(zom.exists(classOf[SharedState], state.id)) shouldBe true
        await(zom.getOwners(classOf[SharedState], state.id)) shouldBe Set(
            owner2.toString)
        zom.delete(classOf[SharedState], state.id, owner2)
        await(zom.exists(classOf[SharedState], state.id)) shouldBe false
    }

    def testDeleteSharedNonExistingOwner(): Unit = {
        val state = new SharedState
        val owner = UUID.randomUUID
        val otherOwner = UUID.randomUUID
        zom.create(state, owner)
        await(zom.exists(classOf[SharedState], state.id)) shouldBe true
        await(zom.getOwners(classOf[SharedState], state.id)) shouldBe Set(
            owner.toString)
        val e = intercept[OwnershipConflictException] {
            zom.delete(classOf[SharedState], state.id, otherOwner)
        }
        await(zom.exists(classOf[SharedState], state.id)) shouldBe true
        await(zom.getOwners(classOf[SharedState], state.id)) shouldBe Set(
            owner.toString)
        e.clazz shouldBe classOf[SharedState].getSimpleName
        e.id shouldBe state.id.toString
        e.currentOwner shouldBe Set(owner.toString)
        e.newOwner shouldBe otherOwner.toString
    }

    def testSharedOwnershipLifecycle(): Unit = {
        val state = new SharedState
        val owner1 = UUID.randomUUID
        val owner2 = UUID.randomUUID
        val owner3 = UUID.randomUUID
        val owner4 = UUID.randomUUID
        zom.create(state, owner1)
        await(zom.exists(classOf[SharedState], state.id)) shouldBe true
        await(zom.getOwners(classOf[SharedState], state.id)) shouldBe Set(
            owner1.toString)
        zom.update(state, owner2, null)
        await(zom.getOwners(classOf[SharedState], state.id)) shouldBe Set(
            owner1.toString, owner2.toString)
        zom.update(state, owner3, null)
        await(zom.getOwners(classOf[SharedState], state.id)) shouldBe Set(
            owner1.toString, owner2.toString, owner3.toString)
        zom.delete(classOf[SharedState], state.id, owner1)
        await(zom.exists(classOf[SharedState], state.id)) shouldBe true
        await(zom.getOwners(classOf[SharedState], state.id)) shouldBe Set(
            owner2.toString, owner3.toString)
        zom.update(state, owner4, null)
        await(zom.getOwners(classOf[SharedState], state.id)) shouldBe Set(
            owner2.toString, owner3.toString, owner4.toString)
        zom.delete(classOf[SharedState], state.id, owner2)
        await(zom.exists(classOf[SharedState], state.id)) shouldBe true
        await(zom.getOwners(classOf[SharedState], state.id)) shouldBe Set(
            owner3.toString, owner4.toString)
        zom.delete(classOf[SharedState], state.id, owner3)
        await(zom.exists(classOf[SharedState], state.id)) shouldBe true
        await(zom.getOwners(classOf[SharedState], state.id)) shouldBe Set(
            owner4.toString)
        zom.delete(classOf[SharedState], state.id, owner4)
        await(zom.exists(classOf[SharedState], state.id)) shouldBe false
        val e = intercept[NotFoundException] {
            await(zom.getOwners(classOf[SharedState], state.id))
        }
        e.clazz shouldBe classOf[SharedState]
        e.id shouldBe state.id
    }

    def testUpdateOwnerSharedSameOwnerThrowIfExists(): Unit = {
        val state = new SharedState
        val owner = UUID.randomUUID
        zom.create(state, owner)
        await(zom.exists(classOf[SharedState], state.id)) shouldBe true
        val e = intercept[OwnershipConflictException] {
            zom.updateOwner(classOf[SharedState], state.id, owner, true)
        }
        e.clazz shouldBe classOf[SharedState].getSimpleName
        e.id shouldBe state.id.toString
        e.currentOwner shouldBe Set(owner.toString)
        e.newOwner shouldBe owner.toString
    }

    def testUpdateOwnerSharedSameOwnerNoThrowIfExists(): Unit = {
        val state = new SharedState
        val owner = UUID.randomUUID
        val path = zom.getOwnerPath(classOf[SharedState], state.id, owner)
        val stat = new Stat
        zom.create(state, owner)
        await(zom.exists(classOf[SharedState], state.id)) shouldBe true
        await(zom.getOwners(classOf[SharedState], state.id)) shouldBe Set(
            owner.toString)
        curator.getData.storingStatIn(stat).forPath(path)
        stat.getEphemeralOwner should not be 0L
        stat.getVersion shouldBe 0
        val mzxid = stat.getMzxid
        zom.updateOwner(classOf[SharedState], state.id, owner, false)
        await(zom.getOwners(classOf[SharedState], state.id)) shouldBe Set(
            owner.toString)
        curator.getData.storingStatIn(stat).forPath(path)
        stat.getEphemeralOwner should not be 0L
        stat.getVersion shouldBe 0
        stat.getMzxid should be > mzxid
    }

    def testUpdateOwnerSharedDifferentOwnerNoRewrite(): Unit = {
        val state = new SharedState
        val owner1 = UUID.randomUUID
        val owner2 = UUID.randomUUID
        zom.create(state, owner1)
        await(zom.exists(classOf[SharedState], state.id)) shouldBe true
        zom.updateOwner(classOf[SharedState], state.id, owner2, false)
        await(zom.getOwners(classOf[SharedState], state.id)) shouldBe Set(
            owner1.toString, owner2.toString)
    }

    def testUpdateOwnerSharedDifferentOwnerWithRewrite(): Unit = {
        val state = new SharedState
        val owner1 = UUID.randomUUID
        val owner2 = UUID.randomUUID
        zom.create(state, owner1)
        await(zom.exists(classOf[SharedState], state.id)) shouldBe true
        zom.updateOwner(classOf[SharedState], state.id, owner2, true)
        await(zom.getOwners(classOf[SharedState], state.id)) shouldBe Set(
            owner1.toString, owner2.toString)
    }

    def testDeleteOwnerExclusiveSameOwner(): Unit = {
        val state = new ExclusiveState
        val owner = UUID.randomUUID
        zom.create(state, owner)
        await(zom.exists(classOf[ExclusiveState], state.id)) shouldBe true
        zom.deleteOwner(classOf[ExclusiveState], state.id, owner)
        await(zom.exists(classOf[ExclusiveState], state.id)) shouldBe true
        await(zom.getOwners(classOf[ExclusiveState], state.id)) shouldBe empty
    }

    def testDeleteOwnerExclusiveDifferentOwner(): Unit = {
        val state = new ExclusiveState
        val owner = UUID.randomUUID
        val otherOwner = UUID.randomUUID
        zom.create(state, owner)
        await(zom.exists(classOf[ExclusiveState], state.id)) shouldBe true
        val e = intercept[OwnershipConflictException] {
            zom.deleteOwner(classOf[ExclusiveState], state.id, otherOwner)
        }
        e.clazz shouldBe classOf[ExclusiveState].getSimpleName
        e.id shouldBe state.id.toString
        e.currentOwner shouldBe Set(owner.toString)
        e.newOwner shouldBe otherOwner.toString
        await(zom.exists(classOf[ExclusiveState], state.id)) shouldBe true
        await(zom.getOwners(classOf[ExclusiveState], state.id)) shouldBe Set(
            owner.toString)
    }

    def testDeleteOwnerSharedSingleSameOwner(): Unit = {
        val state = new SharedState
        val owner = UUID.randomUUID
        zom.create(state, owner)
        await(zom.exists(classOf[SharedState], state.id)) shouldBe true
        zom.deleteOwner(classOf[SharedState], state.id, owner)
        await(zom.exists(classOf[SharedState], state.id)) shouldBe true
        await(zom.getOwners(classOf[SharedState], state.id)) shouldBe Set.empty
    }

    def testDeleteOwnerSharedSingleDifferentOwner(): Unit = {
        val state = new SharedState
        val owner = UUID.randomUUID
        val otherOwner = UUID.randomUUID
        zom.create(state, owner)
        await(zom.exists(classOf[SharedState], state.id)) shouldBe true
        val e = intercept[OwnershipConflictException] {
            zom.deleteOwner(classOf[SharedState], state.id, otherOwner)
        }
        e.clazz shouldBe classOf[SharedState].getSimpleName
        e.id shouldBe state.id.toString
        e.currentOwner shouldBe Set(owner.toString)
        e.newOwner shouldBe otherOwner.toString
        await(zom.exists(classOf[SharedState], state.id)) shouldBe true
        await(zom.getOwners(classOf[SharedState], state.id)) shouldBe Set(
            owner.toString)
    }

    def testDeleteOwnerMultipleExistingOwner(): Unit = {
        val state = new SharedState
        val owner1 = UUID.randomUUID
        val owner2 = UUID.randomUUID
        zom.create(state, owner1)
        await(zom.exists(classOf[SharedState], state.id)) shouldBe true
        await(zom.getOwners(classOf[SharedState], state.id)) shouldBe Set(
            owner1.toString)
        zom.updateOwner(classOf[SharedState], state.id, owner2, false)
        await(zom.getOwners(classOf[SharedState], state.id)) shouldBe Set(
            owner1.toString, owner2.toString)
        zom.deleteOwner(classOf[SharedState], state.id, owner1)
        await(zom.getOwners(classOf[SharedState], state.id)) shouldBe Set(
            owner2.toString)
        zom.deleteOwner(classOf[SharedState], state.id, owner2)
        await(zom.getOwners(classOf[SharedState], state.id)) shouldBe Set.empty
    }

    def testDeleteOwnerMultipleNonExistingOwner(): Unit = {
        val state = new SharedState
        val owner1 = UUID.randomUUID
        val owner2 = UUID.randomUUID
        val otherOwner = UUID.randomUUID
        zom.create(state, owner1)
        await(zom.exists(classOf[SharedState], state.id)) shouldBe true
        await(zom.getOwners(classOf[SharedState], state.id)) shouldBe Set(
            owner1.toString)
        zom.updateOwner(classOf[SharedState], state.id, owner2, false)
        await(zom.getOwners(classOf[SharedState], state.id)) shouldBe Set(
            owner1.toString, owner2.toString)
        val e = intercept[OwnershipConflictException] {
            zom.deleteOwner(classOf[SharedState], state.id, otherOwner)
        }
        await(zom.getOwners(classOf[SharedState], state.id)) shouldBe Set(
            owner1.toString, owner2.toString)
    }

    def testRegularCreateOnExclusiveOwnershipType(): Unit = {
        val state = new ExclusiveState
        intercept[UnsupportedOperationException] {
            zom.create(state)
        }
    }

    def testRegularCreateOnSharedOwnershipType(): Unit = {
        val state = new SharedState
        zom.create(state)
        await(zom.exists(classOf[SharedState], state.id)) shouldBe true
        await(zom.getOwners(classOf[SharedState], state.id)) shouldBe empty
    }

    def testRegularUpdateOnExclusiveOwnershipType(): Unit = {
        val state = new ExclusiveState
        val owner = UUID.randomUUID
        zom.create(state, owner)
        await(zom.exists(classOf[ExclusiveState], state.id)) shouldBe true
        intercept[UnsupportedOperationException] {
            zom.update(state)
        }
    }

    def testRegularUpdateOnSharedOwnershipType(): Unit = {
        val state = new SharedState
        val stateUpdate = new SharedState(state.id, 1)
        val owner = UUID.randomUUID
        zom.create(state, owner)
        await(zom.exists(classOf[SharedState], state.id)) shouldBe true
        await(zom.getOwners(classOf[SharedState], state.id)) shouldBe Set(
            owner.toString)
        zom.update(stateUpdate)
        await(zom.get(classOf[SharedState], state.id)).value shouldBe 1
        await(zom.getOwners(classOf[SharedState], state.id)) shouldBe Set(
            owner.toString)
    }

    def testRegularDeleteOnExclusiveOwnershipType(): Unit = {
        val state = new ExclusiveState
        val owner = UUID.randomUUID
        zom.create(state, owner)
        await(zom.exists(classOf[ExclusiveState], state.id)) shouldBe true
        intercept[UnsupportedOperationException] {
            zom.delete(classOf[ExclusiveState], state.id)
        }
    }

    def testRegularDeleteOnSharedOwnershipTypeForSingleOwner(): Unit = {
        val state = new SharedState
        val owner = UUID.randomUUID
        zom.create(state, owner)
        await(zom.exists(classOf[SharedState], state.id)) shouldBe true
        await(zom.getOwners(classOf[SharedState], state.id)) shouldBe Set(
            owner.toString)
        zom.delete(classOf[SharedState], state.id)
        await(zom.exists(classOf[SharedState], state.id)) shouldBe false
        intercept[NotFoundException] {
            await(zom.getOwners(classOf[SharedState], state.id))
        }
    }

    def testRegularDeleteOnSharedOwnershipTypeForMultipleOwners(): Unit = {
        val state = new SharedState
        val owner1 = UUID.randomUUID
        val owner2 = UUID.randomUUID
        zom.create(state, owner1)
        await(zom.exists(classOf[SharedState], state.id)) shouldBe true
        await(zom.getOwners(classOf[SharedState], state.id)) shouldBe Set(
            owner1.toString)
        zom.update(state, owner2, null)
        await(zom.getOwners(classOf[SharedState], state.id)) shouldBe Set(
            owner1.toString, owner2.toString)
        zom.delete(classOf[SharedState], state.id)
        await(zom.exists(classOf[SharedState], state.id)) shouldBe false
        intercept[NotFoundException] {
            await(zom.getOwners(classOf[SharedState], state.id))
        }
    }

    def testSubscribeExclusiveOwnershipOnCreate(): Unit = {
        val state = new ExclusiveState
        val owner = UUID.randomUUID
        val obs = new AwaitableObserver[Set[String]]()
        zom.create(state, owner)
        zom.ownersObservable(classOf[ExclusiveState], state.id).subscribe(obs)
        obs.await(1.second)
        obs.getOnNextEvents should contain only Set(owner.toString)
    }

    def testSubscribeExclusiveOwnershipOnUpdate(): Unit = {
        val state = new ExclusiveState
        val owner = UUID.randomUUID
        val obs = new AwaitableObserver[Set[String]]()
        zom.create(state, owner)
        zom.ownersObservable(classOf[ExclusiveState], state.id).subscribe(obs)
        obs.await(1.second, 1)
        zom.update(state, owner, null)
        obs.await(1.second)
        obs.getOnNextEvents should contain theSameElementsAs Vector(
            Set(owner.toString), Set(owner.toString))
    }

    def testSubscribeExclusiveOwnershipOnDelete(): Unit = {
        val state = new ExclusiveState
        val owner = UUID.randomUUID
        val obs = new AwaitableObserver[Set[String]]()
        zom.create(state, owner)
        zom.ownersObservable(classOf[ExclusiveState], state.id).subscribe(obs)
        obs.await(1.second, 1)
        zom.delete(classOf[ExclusiveState], state.id, owner)
        obs.await(1.second)
        obs.getOnNextEvents should contain only Set(owner.toString)
        obs.getOnErrorEvents.get(0).getClass shouldBe classOf[
            ParentDeletedException]
    }

    def testSubscribeExclusiveNonExistingObject(): Unit = {
        val owner = UUID.randomUUID
        val id = UUID.randomUUID
        val obs = new AwaitableObserver[Set[String]]()
        zom.ownersObservable(classOf[ExclusiveState], id).subscribe(obs)
        obs.await(1.second)
        obs.getOnNextEvents shouldBe empty
        obs.getOnErrorEvents.get(0).getClass shouldBe classOf[
            ParentDeletedException]
    }

    def testSubscribeSharedOwnershipOnCreate(): Unit = {
        val state = new SharedState
        val owner = UUID.randomUUID
        val obs = new AwaitableObserver[Set[String]]()
        zom.create(state, owner)
        zom.ownersObservable(classOf[SharedState], state.id).subscribe(obs)
        obs.await(1.second)
        obs.getOnNextEvents should contain only Set(owner.toString)
    }

    def testSubscribeSharedOwnershipSingleOwner(): Unit = {
        val state = new SharedState
        val owner = UUID.randomUUID
        val obs = new AwaitableObserver[Set[String]]()
        zom.create(state, owner)
        zom.ownersObservable(classOf[SharedState], state.id).subscribe(obs)
        obs.await(1.second, 1)
        zom.update(state, owner, null)
        obs.await(1.second, 1)
        zom.delete(classOf[SharedState], state.id, owner)
        obs.await(1.second)
        obs.getOnNextEvents should contain theSameElementsAs Vector(
            Set(owner.toString), Set(owner.toString))
        obs.getOnErrorEvents.get(0).getClass shouldBe classOf[
            ParentDeletedException]
    }

    def testSubscribeSharedOwnershipMultipleOwners(): Unit = {
        val state = new SharedState
        val owner1 = UUID.randomUUID
        val owner2 = UUID.randomUUID
        val owner3 = UUID.randomUUID
        val obs = new AwaitableObserver[Set[String]]()
        zom.create(state, owner1)
        zom.ownersObservable(classOf[SharedState], state.id).subscribe(obs)
        obs.await(1.second, 1)
        zom.update(state, owner2, null)
        obs.await(1.second, 1)
        zom.update(state, owner3, null)
        obs.await(1.second, 1)
        zom.delete(classOf[SharedState], state.id, owner1)
        obs.await(1.second, 1)
        zom.delete(classOf[SharedState], state.id, owner2)
        obs.await(1.second, 1)
        zom.delete(classOf[SharedState], state.id, owner3)
        obs.await(1.second)
        obs.getOnNextEvents should contain theSameElementsAs Vector(
            Set(owner1.toString),
            Set(owner1.toString, owner2.toString),
            Set(owner1.toString, owner2.toString, owner3.toString),
            Set(owner2.toString, owner3.toString),
            Set(owner3.toString))
        obs.getOnErrorEvents.get(0).getClass shouldBe classOf[
            ParentDeletedException]
    }

    def testSubscribeOwnerUpdateDelete(): Unit = {
        val state = new SharedState
        val owner1 = UUID.randomUUID
        val owner2 = UUID.randomUUID
        val owner3 = UUID.randomUUID
        val obs = new AwaitableObserver[Set[String]]()
        zom.create(state)
        zom.ownersObservable(classOf[SharedState], state.id).subscribe(obs)
        obs.await(1.second, 1)
        zom.updateOwner(classOf[SharedState], state.id, owner1, false)
        obs.await(1.second, 1)
        zom.updateOwner(classOf[SharedState], state.id, owner2, false)
        obs.await(1.second, 1)
        zom.updateOwner(classOf[SharedState], state.id, owner3, false)
        obs.await(1.second, 1)
        zom.deleteOwner(classOf[SharedState], state.id, owner1)
        obs.await(1.second, 1)
        zom.deleteOwner(classOf[SharedState], state.id, owner2)
        obs.await(1.second, 1)
        zom.deleteOwner(classOf[SharedState], state.id, owner3)
        await(zom.exists(classOf[SharedState], state.id)) shouldBe true
        obs.getOnNextEvents should contain theSameElementsAs Vector(
            Set.empty,
            Set(owner1.toString),
            Set(owner1.toString, owner2.toString),
            Set(owner1.toString, owner2.toString, owner3.toString),
            Set(owner2.toString, owner3.toString),
            Set(owner3.toString),
            Set.empty)
    }
}

private object ZookeeperObjectMapperTests {

    def pojoBridge(id: UUID = UUID.randomUUID, name: String = null,
                   inChainId: UUID = null, outChainId: UUID = null) = {
        new PojoBridge(id, name, inChainId, outChainId)
    }

    def pojoRouter(id: UUID = UUID.randomUUID, name: String = null,
                   inChainId: UUID = null, outChainId: UUID = null) = {
        new PojoRouter(id, name, inChainId, outChainId)
    }

    def pojoPort(name: String = null, peerId: UUID = null,
                 bridgeId: UUID = null, routerId: UUID = null,
                 inChainId: UUID = null, outChainId: UUID = null) = {
        new PojoPort(name, bridgeId, routerId, peerId, inChainId, outChainId)
    }

    def pojoChain(name: String = null) = {
        new PojoChain(name)
    }

    def pojoRule(name: String = null, chainId: UUID = null,
                 portIds: List[UUID] = null) = {
        if (portIds == null) new PojoRule(name, chainId)
        else new PojoRule(name, chainId, portIds:_*)
    }

    def protoNetwork(id: UUID = UUID.randomUUID, name: String = null,
                     inChainId: UUID = null, outChainId: UUID = null): Network = {
        val builder = Network.newBuilder.setId(id.asProto)
        if (name ne null) builder.setName(name)
        if (inChainId ne null) builder.setInboundFilterId(inChainId.asProto)
        if (outChainId ne null) builder.setOutboundFilterId(outChainId.asProto)
        builder.build()
    }

    def protoRouter(id: UUID = UUID.randomUUID, name: String = null,
                    inChainId: UUID = null, outChainId: UUID = null): Router = {
        val builder = Router.newBuilder.setId(id.asProto)
        if (name ne null) builder.setName(name)
        if (inChainId ne null) builder.setInboundFilterId(inChainId.asProto)
        if (outChainId ne null) builder.setOutboundFilterId(outChainId.asProto)
        builder.build()
    }

    def protoPort(id: UUID = UUID.randomUUID, peerId: UUID = null,
                  networkId: UUID = null, routerId: UUID = null,
                  inChainId: UUID = null, outChainId: UUID = null): Port = {
        val builder = Port.newBuilder.setId(id.asProto)
        if (peerId ne null) builder.setPeerId(peerId.asProto)
        if (networkId ne null) builder.setNetworkId(networkId.asProto)
        if (routerId ne null) builder.setRouterId(routerId.asProto)
        if (inChainId ne null) builder.setInboundFilterId(inChainId.asProto)
        if (outChainId ne null) builder.setOutboundFilterId(outChainId.asProto)
        builder.build()
    }

    def protoChain(id: UUID = UUID.randomUUID, name: String = null): Chain = {
        val builder = Chain.newBuilder.setId(id.asProto)
        if (name ne null) builder.setName(name)
        builder.build()
    }

    def protoRule(id: UUID = UUID.randomUUID, chainId: UUID = null,
                  inPortIds: Seq[UUID] = null, outPortIds: Seq[UUID] = null): Rule = {
        val builder = Rule.newBuilder.setId(id.asProto)
        if (chainId ne null) builder.setChainId(chainId.asProto)
        if (inPortIds ne null) builder.addAllInPortIds(inPortIds.map(_.asProto).asJava)
        if (outPortIds ne null) builder.addAllOutPortIds(outPortIds.map(_.asProto).asJava)
        builder.build()
    }

    def await[T](f: Future[T]) =
        Await.result(f, Duration.create(1, TimeUnit.SECONDS))
}
