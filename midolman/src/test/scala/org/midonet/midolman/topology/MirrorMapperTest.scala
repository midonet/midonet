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
package org.midonet.midolman.topology

import java.util.UUID
import scala.concurrent.duration._

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import rx.Observable
import rx.observers.TestObserver

import org.midonet.cluster.data.storage.{NotFoundException, Storage}
import org.midonet.cluster.models.Topology.{Mirror => TopologyMirror}
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.topology.{TopologyMatchers, TopologyBuilder}
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.midolman.simulation.{Mirror => SimulationMirror}
import org.midonet.midolman.topology.TopologyTest.DeviceObserver
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.packets.{IPv4Subnet, IPv4Addr, MAC}
import org.midonet.util.MidonetEventually
import org.midonet.util.reactivex.AwaitableObserver

@RunWith(classOf[JUnitRunner])
class MirrorMapperTest extends MidolmanSpec with TopologyBuilder
                       with TopologyMatchers with MidonetEventually {

    private type MirrorObserver = TestObserver[SimulationMirror]
        with AwaitableObserver[SimulationMirror]

    private var store: Storage = _
    private var vt: VirtualTopology = _
    private var threadId: Long = _

    private final val timeout = 5 seconds

    private var toPort: UUID = _

    protected override def beforeTest(): Unit = {
        vt = injector.getInstance(classOf[VirtualTopology])
        store = injector.getInstance(classOf[MidonetBackend]).store
        threadId = Thread.currentThread.getId

        val br = createBridge()
        store.create(br)
        val port = createBridgePort(bridgeId = Some(br.getId))
        store.create(port)
        toPort = port.getId
    }

    private def createObserver(): DeviceObserver[SimulationMirror] = {
        Given("An observer for the mirror mapper")
        // It is possible to receive the initial notification on the current
        // thread, when the device was notified in the mapper's behavior subject
        // previous to the subscription.
        new DeviceObserver[SimulationMirror](vt)
    }

    private def testMirrorCreated(id: UUID, obs: MirrorObserver): TopologyMirror = {
        Given("A mirror mapper")
        val mapper = new MirrorMapper(id, vt)

        And("A mirror")
        val builder = createMirrorBuilder(id, toPort)
        val mirror = addMirrorCondition(builder,
                           ethSrc = Some(MAC.random()),
                           nwDstIp = Some(new IPv4Subnet(IPv4Addr.random, 24)),
                           nwDstInv = Some(true)).build()

        When("The mirror is created")
        store.create(mirror)

        And("The observer subscribes to an observable on the mapper")
        Observable.create(mapper).subscribe(obs)


        Then("The observer should receive the mirror device")
        obs.awaitOnNext(1, timeout) shouldBe true
        val device = obs.getOnNextEvents.get(0)
        device shouldBeDeviceOf mirror

        mirror
    }

    private def testMirrorUpdated(mirror: TopologyMirror, obs: MirrorObserver,
                                  event: Int): SimulationMirror = {
        When("The mirror is updated")
        store.update(mirror)

        Then("The observer should receive the update")
        obs.awaitOnNext(event, timeout) shouldBe true
        val device = obs.getOnNextEvents.get(event - 1)
        device shouldBeDeviceOf mirror

        device
    }

    private def testMirrorDeleted(mirrorId: UUID, obs: MirrorObserver): Unit = {
        When("The mirror is deleted")
        store.delete(classOf[TopologyMirror], mirrorId)

        Then("The observer should receive a completed notification")
        obs.awaitCompletion(timeout)
        obs.getOnCompletedEvents should not be empty
    }

    feature("Mirror mapper emits notifications for mirror update") {
        scenario("The mapper emits error for non-existing mirror") {
            Given("A mirror identifier")
            val mirrorId = UUID.randomUUID

            And("A mirror mapper")
            val mapper = new MirrorMapper(mirrorId, vt)

            And("An observer to the mirror mapper")
            val obs = createObserver()

            When("The observer subscribes to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should see a NotFoundException")
            obs.awaitCompletion(timeout)
            val e = obs.getOnErrorEvents.get(0).asInstanceOf[NotFoundException]
            e.clazz shouldBe classOf[TopologyMirror]
            e.id shouldBe mirrorId
        }

        scenario("The mapper emits existing mirror") {
            val mirrorId = UUID.randomUUID
            val obs = createObserver()
            testMirrorCreated(mirrorId, obs)
        }

        scenario("The mapper emits new device on mirror update") {
            val mirrorId = UUID.randomUUID
            val obs = createObserver()
            testMirrorCreated(mirrorId, obs)
            val mirrorUpdate = createMirror(id = mirrorId, toPort)
            testMirrorUpdated(mirrorUpdate, obs, event = 2)
        }

        scenario("The mapper completes on mirror delete") {
            val mirrorId = UUID.randomUUID
            val obs = createObserver()
            testMirrorCreated(mirrorId, obs)
            testMirrorDeleted(mirrorId, obs)
        }
    }
}
