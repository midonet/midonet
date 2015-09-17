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

package org.midonet.cluster.util

import java.util.UUID
import java.util.UUID.fromString
import java.util.concurrent.{CountDownLatch, TimeUnit}

import org.junit.runner.RunWith
import org.scalatest._
import org.scalatest.junit.JUnitRunner
import org.scalatest.mock.MockitoSugar

import org.midonet.cluster.data.storage.{InMemoryStorage, SingleValueKey}
import org.midonet.cluster.models.Topology.Vtep
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.services.MidonetBackend.VtepVxgwManager
import org.midonet.util.MidonetEventually

@RunWith(classOf[JUnitRunner])
class SnatcherTest extends FeatureSpec with Matchers
                                       with BeforeAndAfter
                                       with GivenWhenThen
                                       with MockitoSugar
                                       with MidonetEventually {

    var nodeId: UUID = _
    var node2Id: UUID = _
    var vtepId: UUID = _
    var vtep: Vtep = _
    var st: InMemoryStorage = _

    before {
        nodeId = UUID.randomUUID
        node2Id = UUID.randomUUID
        vtepId = UUID.randomUUID
        vtep = Vtep.newBuilder()
                   .setId(UUIDUtil.toProto(vtepId))
                   .build()
        st = new InMemoryStorage
        MidonetBackend.setupBindings(st, st)
    }

    def getCurrOwner(vtep: UUID): UUID = {
        st.getKey(classOf[Vtep], vtep, VtepVxgwManager)
          .toBlocking.first() match {
              case SingleValueKey(_, Some(node), session) => fromString(node)
              case _ => null
        }
    }

    feature("The snatcher manages ownership") {
        scenario("A vtep that is free and then removed") {
            st.create(vtep)

            val snatch = new CountDownLatch(1)
            val release = new CountDownLatch(1)
            val s = Snatcher[Vtep](vtepId, nodeId, st, VtepVxgwManager,
                                   () => snatch.countDown(),
                                   () => release.countDown()
            )
            snatch.await(10, TimeUnit.SECONDS) shouldBe true
            snatch.getCount shouldBe 0
            release.getCount shouldBe 1
            getCurrOwner(vtepId) shouldBe nodeId

            s.giveUp()

            snatch.await(10, TimeUnit.SECONDS) shouldBe true
            snatch.getCount shouldBe 0
            release.getCount shouldBe 0
            getCurrOwner(vtepId) shouldBe null

            st.delete(classOf[Vtep], vtepId)
            snatch.getCount shouldBe 0
            release.getCount shouldBe 0
        }
    }
}
