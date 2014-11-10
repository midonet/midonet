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

import org.apache.curator.framework.CuratorFramework

import org.junit.Assert._
import org.junit.runner.RunWith
import org.midonet.cluster.data.storage.FieldBinding.DeleteAction._
import org.midonet.cluster.data.storage.ZookeeperObjectMapperTest._
import org.midonet.cluster.util.CuratorTestFramework
import org.scalatest.junit.JUnitRunner
import org.scalatest.{Matchers, Suite}
import rx.{Observable, Observer}

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.Duration
import scala.concurrent.Await

@RunWith(classOf[JUnitRunner])
class ZookeeperObjectMapperTests extends Suite
                                 with CuratorTestFramework
                                 with Matchers {
    import org.midonet.cluster.data.storage.ZookeeperObjectMapperTests._

    private var zom: ZookeeperObjectMapper = _

    private var gcRunnable: Runnable = _
    private var gcDone: Boolean = _

    private class MockZookeeperObjectMapper(basePath: String, curator: CuratorFramework)
        extends ZookeeperObjectMapper(basePath, curator) {

        override def scheduleCacheGc(scheduler: ScheduledExecutorService,
                                     runnable: Runnable) = {
            gcRunnable = new Runnable {
                def run() = {
                    gcRunnable.synchronized {
                        gcRunnable.wait()
                    }
                    runnable.run()
                    gcRunnable.synchronized {
                        gcDone = true
                        gcRunnable.notify()
                    }
                }
            }
            scheduler.schedule(gcRunnable, 0, TimeUnit.SECONDS)
        }
    }

    override protected def setup(): Unit = {

        gcDone = false
        zom = new MockZookeeperObjectMapper(ZK_ROOT, curator)

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

    private def createBridge() : PojoBridge = {
        val bridge = PojoBridge()
        zom.create(bridge)
        bridge
    }

    private def addPortToBridge(bId: UUID) = {
        val port = PojoPort(bridgeId = bId)
        zom.create(port)
    }

    private def startGc() = {
        gcRunnable.synchronized {
            gcRunnable.notify()
        }
    }

    private def waitForGc() = {
        gcRunnable.synchronized {
            while (!gcDone) {
                gcRunnable.wait()
            }
        }
    }

    def testSubscribe() {
        val bridge = createBridge()
        val obs = new ObjectSubscription[PojoBridge](2 /* We expect two events */)
        zom.subscribe(classOf[PojoBridge], bridge.id, obs)
        addPortToBridge(bridge.id)

        obs.await(1, TimeUnit.SECONDS)
    }

    def testSubscribeWithGc() = {
        val bridge = createBridge()
        val obs = new ObjectSubscription[PojoBridge](0)
        val sub = zom.subscribe(classOf[PojoBridge], bridge.id, obs)

        zom.subscriptionCount(classOf[PojoBridge], bridge.id) should equal (Option(1))
        sub.unsubscribe()
        zom.subscriptionCount(classOf[PojoBridge], bridge.id) should equal (Option(0))

        startGc()
        waitForGc()

        zom.subscriptionCount(classOf[PojoBridge], bridge.id) should equal (None)
    }

    def testSubscribeAll() {
        createBridge()
        createBridge()

        val obs = new ClassSubscription[PojoBridge](2 /* We expect two events */)
        zom.subscribeAll(classOf[PojoBridge], obs)

        obs.await(1, TimeUnit.SECONDS)
    }

    def testSubscribeAllWithGc() {
        val obs = new ClassSubscription[PojoBridge](0)
        val sub = zom.subscribeAll(classOf[PojoBridge], obs)

        zom.subscriptionCount(classOf[PojoBridge]) should equal (Option(1))
        sub.unsubscribe()
        zom.subscriptionCount(classOf[PojoBridge]) should equal (Option(0))

        startGc()
        waitForGc()

        zom.subscriptionCount(classOf[PojoBridge]) should equal (None)
    }
}

private class ObjectSubscription[T](counter: Int) extends Observer[T] {
    private var countDownLatch = new CountDownLatch(counter)
    var updates: Int = 0
    var event: Option[T] = _
    var ex: Throwable = _

    override def onCompleted() {
        event = None
        countDownLatch.countDown()
    }

    override def onError(e: Throwable) {
        ex = e
        countDownLatch.countDown()
    }

    override def onNext(t: T) {
        updates += 1
        event = Option(t)
        countDownLatch.countDown()
    }

    def await(timeout: Long, unit: TimeUnit) {
        assertTrue(countDownLatch.await(timeout, unit))
    }

    def reset(newCounter: Int) {
        countDownLatch = new CountDownLatch(newCounter)
    }
}

private class ClassSubscription[T](counter: Int) extends Observer[Observable[T]] {
    private var countDownLatch: CountDownLatch = new CountDownLatch(counter)
    val subs = new mutable.MutableList[ObjectSubscription[T]]


    override def onCompleted() {
        fail("Class subscription should not complete.")
    }

    override def onError(e: Throwable) {
        throw new RuntimeException("Got exception from class subscription", e)
    }

    override def onNext(observable: Observable[T]) {
        val sub = new ObjectSubscription[T](1)
        observable.subscribe(sub)
        subs += sub
        countDownLatch.countDown()
    }

    def await(timeout: Long, unit: TimeUnit) {
        assertTrue(countDownLatch.await(timeout, unit))
    }

    def reset(newCounter: Int) {
        countDownLatch = new CountDownLatch(newCounter)
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