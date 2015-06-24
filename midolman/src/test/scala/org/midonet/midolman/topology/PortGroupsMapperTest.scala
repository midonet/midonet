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

import org.midonet.cluster.data.storage.{NotFoundException, Storage}
import org.midonet.cluster.models.Topology.{PortGroup => TopologyPortGroup}
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.midolman.simulation.{PortGroup => SimulationPortGroup}
import org.midonet.midolman.topology.TopologyTest.DeviceObserver
import org.midonet.midolman.util.MidolmanSpec

@RunWith(classOf[JUnitRunner])
class PortGroupsMapperTest extends MidolmanSpec with TopologyBuilder
                           with TopologyMatchers {

    import TopologyBuilder._

    private var vt: VirtualTopology = _
    private var store: Storage = _
    private final val timeout = 5 seconds

    protected override def beforeTest(): Unit = {
        vt = injector.getInstance(classOf[VirtualTopology])
        store = injector.getInstance(classOf[MidonetBackend]).store
    }

    feature("The port groups mapper emits port group devices") {
        scenario("The mapper emits error for non-existing port groups") {
            Given("A port group identifier")
            val id = UUID.randomUUID

            And("A port group mapper")
            val mapper = new PortGroupMapper(id, vt)

            And("An observer to the port group mapper")
            val obs = new DeviceObserver[SimulationPortGroup](vt)

            When("The observer subscribes to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should see a NotFoundException")
            obs.awaitCompletion(timeout)
            val e = obs.getOnErrorEvents.get(0).asInstanceOf[NotFoundException]
            e.clazz shouldBe classOf[TopologyPortGroup]
            e.id shouldBe id
        }

        scenario("The mapper emits existing port groups") {
            Given("A port group")
            val portGroup = createPortGroup()
            store.create(portGroup)

            And("A port group mapper")
            val mapper = new PortGroupMapper(portGroup.getId, vt)

            And("An observer to the port group mapper")
            val obs = new DeviceObserver[SimulationPortGroup](vt)

            When("The observer subscribes to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive the port group")
            obs.awaitOnNext(1, timeout) shouldBe true
            val device = obs.getOnNextEvents.get(0)
            device shouldBeDeviceOf portGroup
        }

        scenario("The mapper emits new device on port group update") {
            Given("A port group")
            val portGroup1 = createPortGroup()
            store.create(portGroup1)

            And("A port group mapper")
            val mapper = new PortGroupMapper(portGroup1.getId, vt)

            And("An observer to the port group mapper")
            val obs = new DeviceObserver[SimulationPortGroup](vt)

            When("The observer subscribes to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive the port group")
            obs.awaitOnNext(1, timeout) shouldBe true
            val device1 = obs.getOnNextEvents.get(0)
            device1 shouldBeDeviceOf portGroup1

            When("The port group is updated")
            val port = createRouterPort()
            store.create(port)
            val portGroup2 = portGroup1
                .setName("port-group")
                .setStateful(true)
                .addPortId(port.getId)
            store.update(portGroup2)

            Then("The observer should receive the update")
            obs.awaitOnNext(2, timeout) shouldBe true
            val device2 = obs.getOnNextEvents.get(1)
            device2 shouldBeDeviceOf portGroup2
        }

        scenario("The mapper completes on port groups delete") {
            Given("A port group")
            val portGroup = createPortGroup()
            store.create(portGroup)

            And("A port group mapper")
            val mapper = new PortGroupMapper(portGroup.getId, vt)

            And("An observer to the port group mapper")
            val obs = new DeviceObserver[SimulationPortGroup](vt)

            When("The observer subscribes to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive the port group")
            obs.awaitOnNext(1, timeout) shouldBe true
            val device = obs.getOnNextEvents.get(0)
            device shouldBeDeviceOf portGroup

            When("The port group is deleted")
            store.delete(classOf[TopologyPortGroup], portGroup.getId)

            Then("The observer should receive a completed notification")
            obs.awaitCompletion(timeout)
            obs.getOnCompletedEvents should not be empty
        }
    }
}
