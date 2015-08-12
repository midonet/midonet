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
import org.scalatest.{GivenWhenThen, Matchers, FlatSpec}

import rx.observers.TestObserver

import org.midonet.cluster.data.storage.KeyType._
import org.midonet.cluster.data.storage.{UnmodifiableStateException, StateResult, ZookeeperObjectMapper}
import org.midonet.cluster.models.Topology.Vtep.{ConnectionState, Information}
import org.midonet.cluster.services.MidonetBackend.{VtepConnectionState, VtepInfo}
import org.midonet.cluster.models.Topology.Vtep
import org.midonet.cluster.util.CuratorTestFramework
import org.midonet.cluster.util.IPAddressUtil._
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.packets.IPv4Addr
import org.midonet.util.reactivex._
import org.midonet.cluster.services.vxgw.data.VtepStorage._

@RunWith(classOf[JUnitRunner])
class VtepStorageTest extends FlatSpec with CuratorTestFramework
                      with Matchers with GivenWhenThen {

    private var storage: ZookeeperObjectMapper = _
    private var ownerId: Long = _
    private val random = new Random
    private final val timeout = 5 seconds

    protected override def setup(): Unit = {
        storage = new ZookeeperObjectMapper(ZK_ROOT, curator)
        ownerId = curator.getZookeeperClient.getZooKeeper.getSessionId
        initAndBuildStorage(storage)
    }

    private def initAndBuildStorage(storage: ZookeeperObjectMapper): Unit = {
        storage.registerClass(classOf[Vtep])
        storage.registerKey(classOf[Vtep], VtepConnectionState, SingleLastWriteWins)
        storage.registerKey(classOf[Vtep], VtepInfo, SingleLastWriteWins)
        storage.build()
    }

    private def createVtep(): Vtep = {
        val vtep = Vtep.newBuilder().setId(randomUuidProto).build()
        storage create vtep
        vtep
    }

    private def createVtepInfo(vtepId: UUID): Information = {
        val info = Information.newBuilder()
            .setName(Random.nextString(10))
            .setDescription(Random.nextString(10))
            .addTunnelAddresses(IPv4Addr.random.asProto)
            .build()

        storage.setVtepInfo(vtepId, info)
               .await(timeout) shouldBe StateResult(ownerId)
        info
    }

    "Store" should "return default information when VTEP does not exist" in {
        val vtepId = UUID.randomUUID
        storage.getVtepInfo(vtepId)
            .await(timeout) shouldBe Information.getDefaultInstance
    }

    "Store" should "return default disconnected when VTEP does not exist" in {
        val vtepId = UUID.randomUUID
        storage.getVtepConnectionState(vtepId)
               .await(timeout) shouldBe ConnectionState.DISCONNECTED
    }

    "Store" should "fail setting VTEP information when VTEP does not exist" in {
        val vtepId = UUID.randomUUID
        intercept[UnmodifiableStateException] {
            storage.setVtepInfo(vtepId, Information.getDefaultInstance)
                   .await(timeout)
        }
    }

    "Store" should "fail setting VTEP connection state when VTEP does not exist" in {
        val vtepId = UUID.randomUUID
        intercept[UnmodifiableStateException] {
            storage.setVtepConnectionState(vtepId, ConnectionState.CONNECTED)
                   .await(timeout)
        }
    }

    "Store" should "return default information when not set" in {
        val vtep = createVtep()
        storage.getVtepInfo(vtep.getId)
            .await(timeout) shouldBe Information.getDefaultInstance
    }

    "Store" should "return disconnected when not set" in {
        val vtep = createVtep()
        storage.getVtepConnectionState(vtep.getId)
               .await(timeout) shouldBe ConnectionState.DISCONNECTED
    }

    "Store" should "set and get the VTEP information" in {
        val vtep = createVtep()
        val info = createVtepInfo(vtep.getId)
        storage.getVtepInfo(vtep.getId).await(timeout) shouldBe info
    }

    "Store" should "set and get the VTEP name" in {
        val vtep = createVtep()
        val name = Random.nextString(10)

        storage.setVtepName(vtep.getId, name)
               .await(timeout) shouldBe StateResult(ownerId)
        storage.getVtepInfo(vtep.getId).await(timeout).getName shouldBe name
    }

    "Store" should "set and get the VTEP description" in {
        val vtep = createVtep()
        val description = Random.nextString(10)

        storage.setVtepDescription(vtep.getId, description)
               .await(timeout) shouldBe StateResult(ownerId)
        storage.getVtepInfo(vtep.getId)
               .await(timeout).getDescription shouldBe description
    }

    "Store" should "set and get the VTEP tunnel addresses" in {
        val vtep = createVtep()
        val address = IPv4Addr.random

        storage.setVtepTunnelAddresses(vtep.getId, Seq(address))
               .await(timeout) shouldBe StateResult(ownerId)
        storage.getVtepInfo(vtep.getId)
               .await(timeout).getTunnelAddressesList should contain only address.asProto
    }

    "Store" should "update the VTEP name" in {
        val vtep = createVtep()
        val info = createVtepInfo(vtep.getId)
        val name = Random.nextString(10)

        storage.setVtepName(vtep.getId, name)
               .await(timeout) shouldBe StateResult(ownerId)
        storage.getVtepInfo(vtep.getId).await(timeout) shouldBe info
               .toBuilder.setName(name).build()
    }

    "Store" should "update the VTEP description" in {
        val vtep = createVtep()
        val info = createVtepInfo(vtep.getId)
        val description = Random.nextString(10)

        storage.setVtepDescription(vtep.getId, description)
               .await(timeout) shouldBe StateResult(ownerId)
        storage.getVtepInfo(vtep.getId).await(timeout) shouldBe info
               .toBuilder.setDescription(description).build()
    }

    "Store" should "update the VTEP tunnel addresses" in {
        val vtep = createVtep()
        val info = createVtepInfo(vtep.getId)
        val address = IPv4Addr.random

        storage.setVtepTunnelAddresses(vtep.getId, Seq(address))
               .await(timeout) shouldBe StateResult(ownerId)
        storage.getVtepInfo(vtep.getId).await(timeout) shouldBe info
               .toBuilder.clearTunnelAddresses.addTunnelAddresses(address.asProto)
               .build()
    }

    "Store" should "set and get the VTEP connection state" in {
        val vtep = createVtep()

        storage.setVtepConnectionState(vtep.getId, ConnectionState.CONNECTED)
               .await(timeout)
        storage.getVtepConnectionState(vtep.getId)
               .await(timeout) shouldBe ConnectionState.CONNECTED
        storage.setVtepConnectionState(vtep.getId, ConnectionState.ERROR)
               .await(timeout)
        storage.getVtepConnectionState(vtep.getId)
               .await(timeout) shouldBe ConnectionState.ERROR
    }

    "Store observable" should "emit notifications on information updates" in {
        val vtep = createVtep()
        val obs = new TestObserver[Information]
                      with AwaitableObserver[Information]
        storage.vtepInfoObservable(vtep.getId).subscribe(obs)

        obs.awaitOnNext(1, timeout) shouldBe true
        obs.getOnNextEvents.get(0) shouldBe Information.getDefaultInstance

        val info1 = createVtepInfo(vtep.getId)
        obs.awaitOnNext(2, timeout) shouldBe true
        obs.getOnNextEvents.get(1) shouldBe info1

        val info2 = createVtepInfo(vtep.getId)
        obs.awaitOnNext(3, timeout) shouldBe true
        obs.getOnNextEvents.get(2) shouldBe info2

        storage.delete(classOf[Vtep], vtep.getId)
        obs.awaitCompletion(timeout)
    }

    "Store observable" should "emit notifications on connection state update" in {
        val vtep = createVtep()
        val obs = new TestObserver[ConnectionState]
                      with AwaitableObserver[ConnectionState]
        storage.vtepConnectionStateObservable(vtep.getId).subscribe(obs)

        obs.awaitOnNext(1, timeout) shouldBe true
        obs.getOnNextEvents.get(0) shouldBe ConnectionState.DISCONNECTED

        storage.setVtepConnectionState(vtep.getId, ConnectionState.CONNECTED)
               .await(timeout)
        obs.awaitOnNext(2, timeout) shouldBe true
        obs.getOnNextEvents.get(1) shouldBe ConnectionState.CONNECTED


        storage.setVtepConnectionState(vtep.getId, ConnectionState.ERROR)
               .await(timeout)
        obs.awaitOnNext(3, timeout) shouldBe true
        obs.getOnNextEvents.get(2) shouldBe ConnectionState.ERROR

        storage.delete(classOf[Vtep], vtep.getId)
        obs.awaitCompletion(timeout)
    }

}
