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

package org.midonet.cluster.services.vxgw.data

import java.util.UUID

import scala.concurrent.duration._
import scala.util.Random

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FlatSpec, GivenWhenThen, Matchers}
import rx.observers.TestObserver

import org.midonet.cluster.data.storage.KeyType._
import org.midonet.cluster.data.storage.{StateResult, UnmodifiableStateException, ZookeeperObjectMapper}
import org.midonet.cluster.models.State.{VtepConfiguration, VtepConnectionState}
import org.midonet.cluster.models.Topology.Vtep
import org.midonet.cluster.services.MidonetBackend.{ClusterNamespaceId, VtepConfig, VtepConnState}
import org.midonet.cluster.services.vxgw.data.VtepStateStorage._
import org.midonet.cluster.util.CuratorTestFramework
import org.midonet.cluster.util.IPAddressUtil._
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.packets.IPv4Addr
import org.midonet.util.reactivex._

@RunWith(classOf[JUnitRunner])
class VtepStateStorageTest extends FlatSpec with CuratorTestFramework
                                   with Matchers with GivenWhenThen {

    private var storage: ZookeeperObjectMapper = _
    private var ownerId: Long = _
    private val random = new Random
    private final val timeout = 5 seconds

    protected override def setup(): Unit = {
        storage = new ZookeeperObjectMapper(config, "zoom",
                                            ClusterNamespaceId.toString,
                                            curator)
        ownerId = curator.getZookeeperClient.getZooKeeper.getSessionId
        initAndBuildStorage(storage)
    }

    private def initAndBuildStorage(storage: ZookeeperObjectMapper): Unit = {
        storage.registerClass(classOf[Vtep])
        storage.registerKey(classOf[Vtep], VtepConfig, SingleLastWriteWins)
        storage.registerKey(classOf[Vtep], VtepConnState, SingleLastWriteWins)
        storage.build()
    }

    private def createVtep(): Vtep = {
        val vtep = Vtep.newBuilder().setId(randomUuidProto).build()
        storage create vtep
        vtep
    }

    private def createVtepConfig(vtepId: UUID): VtepConfiguration = {
        val info = VtepConfiguration.newBuilder()
            .setName(Random.nextString(10))
            .setDescription(Random.nextString(10))
            .addTunnelAddresses(IPv4Addr.random.asProto)
            .build()

        storage.setVtepConfig(vtepId, info)
               .await(timeout) shouldBe StateResult(ownerId)
        info
    }

    "Store" should "return default information when VTEP does not exist" in {
        val vtepId = UUID.randomUUID
        storage.getVtepConfig(vtepId)
            .await(timeout) shouldBe VtepConfiguration.getDefaultInstance
    }

    "Store" should "return default disconnected when VTEP does not exist" in {
        val vtepId = UUID.randomUUID
        storage.getVtepConnectionState(vtepId)
               .await(timeout) shouldBe VtepConnectionState.VTEP_DISCONNECTED
    }

    "Store" should "fail setting VTEP information when VTEP does not exist" in {
        val vtepId = UUID.randomUUID
        intercept[UnmodifiableStateException] {
            storage.setVtepConfig(vtepId, VtepConfiguration.getDefaultInstance)
                   .await(timeout)
        }
    }

    "Store" should "fail setting VTEP connection state when VTEP does not exist" in {
        val vtepId = UUID.randomUUID
        intercept[UnmodifiableStateException] {
            storage.setVtepConnectionState(vtepId,
                                           VtepConnectionState.VTEP_CONNECTED)
                   .await(timeout)
        }
    }

    "Store" should "return default information when not set" in {
        val vtep = createVtep()
        storage.getVtepConfig(vtep.getId)
            .await(timeout) shouldBe VtepConfiguration.getDefaultInstance
    }

    "Store" should "return disconnected when not set" in {
        val vtep = createVtep()
        storage.getVtepConnectionState(vtep.getId)
               .await(timeout) shouldBe VtepConnectionState.VTEP_DISCONNECTED
    }

    "Store" should "set and get the VTEP information" in {
        val vtep = createVtep()
        val info = createVtepConfig(vtep.getId)
        storage.getVtepConfig(vtep.getId).await(timeout) shouldBe info
    }

    "Store" should "set and get the VTEP connection state" in {
        val vtep = createVtep()

        storage.setVtepConnectionState(vtep.getId,
                                       VtepConnectionState.VTEP_CONNECTED)
               .await(timeout)
        storage.getVtepConnectionState(vtep.getId)
               .await(timeout) shouldBe VtepConnectionState.VTEP_CONNECTED
        storage.setVtepConnectionState(vtep.getId,
                                       VtepConnectionState.VTEP_ERROR)
               .await(timeout)
        storage.getVtepConnectionState(vtep.getId)
               .await(timeout) shouldBe VtepConnectionState.VTEP_ERROR
    }

    "Store observable" should "emit notifications on information updates" in {
        val vtep = createVtep()
        val obs = new TestAwaitableObserver[VtepConfiguration]
        storage.vtepConfigObservable(vtep.getId).subscribe(obs)

        obs.awaitOnNext(1, timeout) shouldBe true
        obs.getOnNextEvents.get(0) shouldBe VtepConfiguration.getDefaultInstance

        val info1 = createVtepConfig(vtep.getId)
        obs.awaitOnNext(2, timeout) shouldBe true
        obs.getOnNextEvents.get(1) shouldBe info1

        val info2 = createVtepConfig(vtep.getId)
        obs.awaitOnNext(3, timeout) shouldBe true
        obs.getOnNextEvents.get(2) shouldBe info2

        storage.delete(classOf[Vtep], vtep.getId)
        obs.awaitCompletion(timeout)
    }

    "Store observable" should "emit notifications on connection state update" in {
        val vtep = createVtep()
        val obs = new TestObserver[VtepConnectionState]
                      with AwaitableObserver[VtepConnectionState]
        storage.vtepConnectionStateObservable(vtep.getId).subscribe(obs)

        obs.awaitOnNext(1, timeout) shouldBe true
        obs.getOnNextEvents.get(0) shouldBe VtepConnectionState.VTEP_DISCONNECTED

        storage.setVtepConnectionState(vtep.getId,
                                       VtepConnectionState.VTEP_CONNECTED)
               .await(timeout)
        obs.awaitOnNext(2, timeout) shouldBe true
        obs.getOnNextEvents.get(1) shouldBe VtepConnectionState.VTEP_CONNECTED


        storage.setVtepConnectionState(vtep.getId,
                                       VtepConnectionState.VTEP_ERROR)
               .await(timeout)
        obs.awaitOnNext(3, timeout) shouldBe true
        obs.getOnNextEvents.get(2) shouldBe VtepConnectionState.VTEP_ERROR

        storage.delete(classOf[Vtep], vtep.getId)
        obs.awaitCompletion(timeout)
    }

}
