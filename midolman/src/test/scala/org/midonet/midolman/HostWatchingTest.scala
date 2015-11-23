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
package org.midonet.midolman

import java.util.UUID

import scala.concurrent.duration._

import akka.util.Timeout
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.midolman.topology.devices.Host
import org.midonet.midolman.topology.VirtualToPhysicalMapper
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.util.MidonetEventually
import org.midonet.util.reactivex.TestAwaitableObserver

@RunWith(classOf[JUnitRunner])
class HostWatchingTest extends MidolmanSpec
                       with MidonetEventually {

    implicit val askTimeout: Timeout = 1 second

    val hostToWatch = UUID.randomUUID()
    val hostName = "foo"

    override def beforeTest() {
        newHost(hostName, hostToWatch)
    }

    feature("midolman tracks hosts in the cluster correctly") {
        scenario("The virtual to physical mapper relays host updates") {
            Given("A host observer")
            val obs = new TestAwaitableObserver[Host]

            When("The observer subscribes to the host updates")
            VirtualToPhysicalMapper.hosts(hostToWatch).subscribe(obs)

            Then("The observer receives the host")
            obs.getOnNextEvents.get(0).id shouldBe hostToWatch
            obs.getOnNextEvents.get(0) should not be 'alive

            When("Making the port alive")
            makeHostAlive(hostToWatch)
            obs.getOnNextEvents.get(1).id shouldBe hostToWatch
            obs.getOnNextEvents.get(1) shouldBe 'alive
        }
    }
}
