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
import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.concurrent.{Await, ExecutionContext, Future}

import org.apache.curator.framework.CuratorFramework
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{Matchers, Suite}

import org.midonet.cluster.data.storage.FieldBinding.DeleteAction._
import org.midonet.cluster.data.storage.ZookeeperObjectMapperTest._
import org.midonet.cluster.util.{ClassAwaitableObserver, CuratorTestFramework}
import org.midonet.util.reactivex.AwaitableObserver

@RunWith(classOf[JUnitRunner])
class ZookeeperObjectMapperTests extends Suite
                                 with CuratorTestFramework
                                 with Matchers {
    import org.midonet.cluster.data.storage.ZookeeperObjectMapperTests._

    private val oneSecond = new FiniteDuration(1, TimeUnit.SECONDS)

    private val gcSynchronization = new Object
    private var zom: ZookeeperObjectMapper = _
    @volatile private var gcStarted: Boolean = _
    @volatile private var gcDone: Boolean = _

    private def gcRunnable(runnable: Runnable): Runnable = new Runnable {
        def run() = {
            gcSynchronization.synchronized {
                while (!gcStarted) {
                    gcSynchronization.wait()
                }
            }
            runnable.run()
            gcSynchronization.synchronized {
                gcDone = true
                gcSynchronization.notify()
            }
        }
    }

    private class MockZookeeperObjectMapper(basePath: String, curator: CuratorFramework)
        extends ZookeeperObjectMapper(basePath, curator) {

        override def scheduleCacheGc(scheduler: ScheduledExecutorService,
                                     runnable: Runnable) = {
            scheduler.schedule(gcRunnable(runnable), 0, TimeUnit.SECONDS)
        }
    }

    override protected def setup(): Unit = {
        gcStarted = false
        gcDone = false
        zom = new MockZookeeperObjectMapper(ZK_ROOT, curator)
        initAndBuildZoom(zom)
    }

    private def initAndBuildZoom(zom: ZookeeperObjectMapper) {
        List(classOf[PojoBridge], classOf[PojoRouter], classOf[PojoPort],
             classOf[PojoChain], classOf[PojoRule]).foreach {
            clazz => zom.registerClass(clazz)
        }

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

        zom.build()
    }

    override protected def teardown(): Unit = {
        // This is to make sure that the gc thread is not blocked in tests
        // where no garbage collection is performed.
        startGc()
        waitForGc()
    }

    def testMultiCreate() {
        val bridge = PojoBridge()
        val port = PojoPort(bridgeId = bridge.id)
        zom.multi(List(CreateOp(bridge), CreateOp(port)))

        val updatedBridge = await(zom.get(classOf[PojoBridge], bridge.id))
        updatedBridge.portIds.asScala should equal(List(port.id))
    }

    def testMultiCreateUpdateAndDelete() {
        val chain = PojoChain(name = "chain1")
        zom.create(chain)

        val chain2 = PojoChain(name = "chain2")
        val bridge = PojoBridge(inChainId = chain.id)
        val bridgeUpdate = PojoBridge(id = bridge.id,
                                      inChainId = chain.id,
                                      outChainId = chain2.id)
        val router = PojoRouter(outChainId = chain.id)
        val routerUpdate = PojoRouter(id = router.id,
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

    def testMultiUpdateAndCascadingDelete() {
        val chain1 = PojoChain(name = "chain1")
        val rule1 = PojoRule(name = "rule1", chainId = chain1.id)
        val rule2 = PojoRule(name = "rule2", chainId = chain1.id)
        val rule3 = PojoRule(name = "rule3", chainId = chain1.id)
        zom.multi(List(CreateOp(chain1), CreateOp(rule1),
                       CreateOp(rule2), CreateOp(rule3)))

        val chain2 = PojoChain(name = "chain2")
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

    def testMultiWithUpdateOfDeletedObject() {
        val chain = PojoChain()
        val rule = PojoRule(chainId = chain.id)
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

    def testMultiWithRedundantDelete() {
        val chain = PojoChain()
        val rule = PojoRule(chainId = chain.id)
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

    def testMultiIdGet() {
        implicit val es = ExecutionContext.global
        val chains = List("chain0", "chain1", "chain2").map(PojoChain)
        zom.multi(chains.map(CreateOp))
        val twoIds = chains.take(2).map(_.id).asJava
        val twoChains = await(
            Future.sequence(zom.getAll(classOf[PojoChain], twoIds.asScala))
        )
        twoChains.map(_.name) should equal(List("chain0", "chain1"))
    }

    def testDeleteIfExists() {
        zom.deleteIfExists(classOf[PojoBridge], UUID.randomUUID)
    }

    def testDeleteIfExistsOnDeletedObject() {
        val bridge = PojoBridge()
        zom.create(bridge)
        zom.delete(classOf[PojoBridge], bridge.id)
        // Idempotent delete.
        zom.deleteIfExists(classOf[PojoBridge], bridge.id)
    }

    def testDeleteIfExistsOnDeletedObjectMulti() {
        val bridge = PojoBridge()
        zom.create(bridge)
        zom.multi(List(DeleteOp(classOf[PojoBridge], bridge.id),
                       DeleteOp(classOf[PojoBridge], bridge.id, true)))
    }

    def testMultiWithRedundantDeleteIfExists() {
        val chain = PojoChain()
        val rule = PojoRule(chainId = chain.id)
        // The following two multis cannot be turned into a single multi.
        // Apparently it is a current limitation of ZOOM that in a single multi
        // one cannot delete an object that's just been created due to a race
        // to the backend ZooKeeper.
        zom.multi(List(CreateOp(chain), CreateOp(rule)))
        zom.multi(List(DeleteOp(classOf[PojoChain], chain.id),
                       DeleteOp(classOf[PojoRule], rule.id, true)))
    }

    private def createBridge() : PojoBridge = {
        val bridge = PojoBridge()
        zom.create(bridge)
        bridge
    }

    private def addPortToBridge(bridge: PojoBridge) = {
        val port = PojoPort(bridgeId = bridge.id)
        zom.create(port)
    }

    private def startGc() = {
        gcSynchronization.synchronized {
            gcStarted = true
            gcSynchronization.notify()
        }
    }

    private def waitForGc() = {
        gcSynchronization.synchronized {
            while (!gcDone) {
                gcSynchronization.wait()
            }
        }
    }
    def testSubscribe() {
        val bridge = createBridge()
        val obs = new AwaitableObserver[PojoBridge](1)
        zom.subscribe(classOf[PojoBridge], bridge.id, obs)
        obs.await(oneSecond)
        obs.reset(1)
        addPortToBridge(bridge)
        obs.await(oneSecond)
    }

    def testSubscribeWithGc() = {
        val bridge = createBridge()
        val obs = new AwaitableObserver[PojoBridge](0)
        val sub = zom.subscribe(classOf[PojoBridge], bridge.id, obs)

        zom.subscriptionCount(classOf[PojoBridge], bridge.id).get should be(1)
        sub.unsubscribe()
        zom.subscriptionCount(classOf[PojoBridge], bridge.id).get should be(0)

        startGc()
        waitForGc()

        zom.subscriptionCount(classOf[PojoBridge], bridge.id) should be(None)
    }

    def testSubscribeAll() {
        createBridge()
        createBridge()

        val obs = new ClassAwaitableObserver[PojoBridge](2 /* We expect two events */)
        zom.subscribeAll(classOf[PojoBridge], obs)

        obs.await(oneSecond, 0)
    }

    def testSubscribeAllWithGc() {
        val obs = new ClassAwaitableObserver[PojoBridge](0)
        val sub = zom.subscribeAll(classOf[PojoBridge], obs)

        zom.subscriptionCount(classOf[PojoBridge]).get should be(1)
        sub.unsubscribe()
        zom.subscriptionCount(classOf[PojoBridge]).get should be(0)

        startGc()
        waitForGc()

        zom.subscriptionCount(classOf[PojoBridge]) should be(None)
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
        val bridge = PojoBridge()
        val port = PojoPort(bridgeId = bridge.id)
        zom.multi(List(CreateOp(bridge), CreateOp(port)))
        await(zom.exists(classOf[PojoBridge], bridge.id)) should equal (true)
        await(zom.exists(classOf[PojoPort], port.id)) should equal (true)

        zom.flush()
        await(zom.exists(classOf[PojoBridge], bridge.id)) should equal (false)
        await(zom.exists(classOf[PojoPort], port.id)) should equal (false)

        // After flushing, ZOOM should be able to store new objects again.
        val bridge2 = PojoBridge()
        val port2 = PojoPort(bridgeId = bridge2.id)
        zom.multi(List(CreateOp(bridge2), CreateOp(port2)))
        await(zom.exists(classOf[PojoBridge], bridge2.id)) should equal (true)
        await(zom.exists(classOf[PojoPort], port2.id)) should equal (true)
    }
}

private object ZookeeperObjectMapperTests {

    def PojoBridge(id: UUID = UUID.randomUUID, name: String = null,
                   inChainId: UUID = null, outChainId: UUID = null) = {
        new PojoBridge(id, name, inChainId, outChainId)
    }

    def PojoRouter(id: UUID = UUID.randomUUID, name: String = null,
                   inChainId: UUID = null, outChainId: UUID = null) = {
        new PojoRouter(id, name, inChainId, outChainId)
    }

    def PojoPort(name: String = null, peerId: UUID = null,
                 bridgeId: UUID = null, routerId: UUID = null,
                 inChainId: UUID = null, outChainId: UUID = null) = {
        new PojoPort(name, bridgeId, routerId, peerId, inChainId, outChainId)
    }

    def PojoChain(name: String = null) = {
        new PojoChain(name)
    }

    def PojoRule(name: String = null, chainId: UUID = null,
                 portIds: List[UUID] = null) = {
        if (portIds == null) new PojoRule(name, chainId)
        else new PojoRule(name, chainId, portIds:_*)
    }

    def await[T](f: Future[T]) =
        Await.result(f, Duration.create(1, TimeUnit.SECONDS))
}
