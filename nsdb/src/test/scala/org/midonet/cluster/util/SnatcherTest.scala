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
import java.util.concurrent.atomic.AtomicInteger
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
            val wasSnatched = new CountDownLatch(1)
            val wasReleased = new CountDownLatch(1)
            Snatcher[Vtep](vtepId, nodeId, st, VtepVxgwManager,
                           () => wasSnatched.countDown(),
                           () => wasReleased.countDown()
            )
            wasSnatched.await(10, TimeUnit.SECONDS) shouldBe true
            wasReleased.getCount shouldBe 1

            getCurrOwner(vtepId) shouldBe nodeId

            st.delete(classOf[Vtep], vtepId)
            wasReleased.await(10, TimeUnit.SECONDS) shouldBe true
            wasSnatched.getCount shouldBe 0
        }

        scenario("Another snatcher is competing for ownership") {
            st.create(vtep)
            val snatch = new AtomicInteger()
            val release = new AtomicInteger()

            val s1 = Snatcher[Vtep](vtepId, nodeId, st, VtepVxgwManager,
                                    () => snatch.incrementAndGet(),
                                    () => release.incrementAndGet())
            val s2 = Snatcher[Vtep](vtepId, node2Id, st, VtepVxgwManager,
                                    () => snatch.incrementAndGet(),
                                    () => release.incrementAndGet())

            eventually {
                snatch.get() shouldBe 1
                release.get() shouldBe 0
            }

            val (owner, nonOwner) = getCurrOwner(vtepId) match {
                case null => fail()
                case n if n == nodeId => (s1, s2)
                case n => (s2, s1)
            }

            getCurrOwner(vtepId) shouldBe owner.nodeId

            owner.giveUp()

            eventually {
                snatch.get() shouldBe 2
                release.get() shouldBe 1
                getCurrOwner(vtepId) shouldBe nonOwner.nodeId
            }

            st.delete(classOf[Vtep], vtepId)

            eventually {
                snatch.get() shouldBe 2
                release.get() shouldBe 2
                getCurrOwner(vtepId) shouldBe null
            }
        }

    }
}
