/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.cluster.data.storage

import java.util.UUID

import org.junit.runner.RunWith
import org.midonet.cluster.data.storage.FieldBinding.DeleteAction._
import org.midonet.cluster.data.storage.ZookeeperObjectMapperTest._
import org.midonet.cluster.util.CuratorTestFramework
import org.scalatest.{Suite, Matchers}
import org.scalatest.junit.JUnitRunner

import scala.collection.JavaConverters._

@RunWith(classOf[JUnitRunner])
class ZookeeperObjectMapperTests extends Suite
                                 with CuratorTestFramework
                                 with Matchers {
    import ZookeeperObjectMapperTests._

    private var zom: ZookeeperObjectMapper = _

    override protected def setup(): Unit = {
        zom = new ZookeeperObjectMapper(ZK_ROOT, curator)

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
    }

    def testMultiCreate() {
        val bridge = PojoBridge()
        val port = PojoPort(bridgeId = bridge.id)
        zom.multi(List(CreateOp(bridge), CreateOp(port)))

        val updatedBridge = zom.get(classOf[PojoBridge], bridge.id)
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

        val updatedChain2 = zom.get(classOf[PojoChain], chain2.id)
        updatedChain2.bridgeIds.asScala should equal(List(bridge.id))
        updatedChain2.routerIds.asScala should equal(List(router.id))

        val updatedBridge = zom.get(classOf[PojoBridge], bridge.id)
        updatedBridge.inChainId shouldBe null
        updatedBridge.outChainId should equal(chain2.id)

        val updatedRouter = zom.get(classOf[PojoRouter], router.id)
        updatedRouter.inChainId should equal(chain2.id)
        updatedRouter.outChainId shouldBe null

        zom.exists(classOf[PojoChain], chain.id) shouldBe false
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

        zom.exists(classOf[PojoChain], chain1.id) shouldBe false
        zom.exists(classOf[PojoRule], rule1.id) shouldBe false
        zom.exists(classOf[PojoRule], rule2.id) shouldBe false

        val updatedChain2 = zom.get(classOf[PojoChain], chain2.id)
        updatedChain2.ruleIds.asScala should equal(List(rule3.id))

        val updatedRule3 = zom.get(classOf[PojoRule], rule3.id)
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

}