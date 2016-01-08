package org.midonet.cluster.services.c3po.translators.LoadBalancerPoolTranslatorIT

import java.util.UUID

import org.midonet.cluster.C3POMinionTestBase
import org.midonet.cluster.data.neutron.NeutronResourceType.{Pool => PoolType, Router => RouterType}
import org.midonet.cluster.models.Topology.{LoadBalancer, Pool, Router}
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.util.concurrent.toFutureOps

class LoadBalancerPoolTranslatorIT extends C3POMinionTestBase {

    "The delete of a Pool" should "also delete the load balancers" in {
        val rId = UUID.randomUUID
        val p1Id = UUID.randomUUID
        val p2Id = UUID.randomUUID
        val rJson = routerJson(rId)
        val p1Json = poolJson(p1Id, rId) // neutron pools
        val p2Json = poolJson(p2Id, rId)
        insertCreateTask(1, RouterType, rJson, rId)
        insertCreateTask(2, PoolType, p1Json, p1Id)
        insertCreateTask(3, PoolType, p2Json, p2Id)

        var lbId: UUID = null
        eventually {
            val r = storage.get(classOf[Router], rId).await()
            val p1 = storage.get(classOf[Pool], p1Id).await()
            val p2 = storage.get(classOf[Pool], p2Id).await()
            val lb = storage.get(classOf[LoadBalancer], p1.getLoadBalancerId).await()
            r.getLoadBalancerId shouldBe p1.getLoadBalancerId
            p2.getLoadBalancerId shouldBe p1.getLoadBalancerId
            r.getId shouldBe lb.getRouterId
            lb.getPoolIdsCount shouldBe 2
            lbId = lb.getId.asJava
        }
        insertDeleteTask(4, PoolType, p1Id)
        eventually {
            storage.exists(classOf[Pool], p1Id).await() shouldBe false
            storage.exists(classOf[Pool], p2Id).await() shouldBe true
            val lb = storage.get(classOf[LoadBalancer], lbId).await()
            lb.getPoolIdsCount shouldBe 1
            storage.get(classOf[Router], rId).await().hasLoadBalancerId shouldBe true
        }
        // When the last pool is deleted, the LB goes with it
        insertDeleteTask(5, PoolType, p2Id)
        eventually {
            storage.get(classOf[Router], rId).await().hasLoadBalancerId shouldBe false
            storage.exists(classOf[LoadBalancer], lbId).await() shouldBe false
            storage.exists(classOf[Pool], p1Id).await() shouldBe false
            storage.exists(classOf[Pool], p2Id).await() shouldBe false
        }

    }

}
