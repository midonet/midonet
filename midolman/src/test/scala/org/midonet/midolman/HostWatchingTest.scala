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
import org.midonet.midolman.topology.{VirtualToPhysicalMapper => VTPM}
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.midolman.util.mock.MessageAccumulator
import org.midonet.util.MidonetEventually

@RunWith(classOf[JUnitRunner])
class HostWatchingTest extends MidolmanSpec
                       with MidonetEventually {

    implicit val askTimeout: Timeout = 1 second

    val hostToWatch = UUID.randomUUID()
    val hostName = "foo"

    override def beforeTest() {
        newHost(hostName, hostToWatch)
    }

    /*
    private def interceptHost(): Host = {
        VTPM.tryAsk(VTPM.HostRequest(hostToWatch, true))
    }

    feature("midolman tracks hosts in the cluster correctly") {
        scenario("VTPM gets a host and receives updates from its manager") {
            VTPM ! HostRequest(hostToWatch)

            val host = interceptHost()
            host.id should equal (hostToWatch)
            host should not be ('alive)

            makeHostAlive(hostToWatch)
            eventually { interceptHost() should be ('alive) }
        }
    }*/
}
