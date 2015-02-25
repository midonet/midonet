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

package org.midonet.brain.services.c3po.translators

import scala.concurrent.Promise
import org.junit.runner.RunWith
import org.mockito.Mockito.{mock, when}
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfter, FlatSpec, Matchers}
import org.midonet.brain.services.c3po.{midonet, neutron}
import org.midonet.cluster.data.storage.ReadOnlyStorage
import org.midonet.cluster.models.ModelsUtil._
import org.midonet.cluster.models.Neutron.NeutronTunnelZone
import org.midonet.cluster.models.Topology.TunnelZone
import org.midonet.cluster.util.IPAddressUtil.toProto
import org.midonet.cluster.util.UUIDUtil.randomUuidProto

/**
 * Tests TunnelZoneTranslator.
 */
@RunWith(classOf[JUnitRunner])
class TunnelZoneTranslatorTest extends FlatSpec with BeforeAndAfter
                                                with Matchers {
    protected var storage: ReadOnlyStorage = _
    protected var translator: TunnelZoneTranslator = _

    before {
        storage = mock(classOf[ReadOnlyStorage])
        translator = new TunnelZoneTranslator(storage)

        when(storage.get(classOf[TunnelZone], updatedTunnelZoneId))
            .thenReturn(Promise.successful(mTunnelZoneBeforeUpdate).future)
    }

    private val tunnelZoneId = randomUuidProto
    private val nTunnelZone = nTunnelZoneFromTxt(s"""
        id { $tunnelZoneId }
        name: "tz0"
        type: GRE
        """)
    private val mTunnelZone = mTunnelZoneFromTxt(s"""
        id { $tunnelZoneId }
        name: "tz0"
        type: GRE
        """)

    "TunnelZone Create" should "add create a new MidoNet Tunnel Zone" in {
        val midoOps = translator.translate(neutron.Create(nTunnelZone))

        midoOps should contain (midonet.Create(mTunnelZone))
    }

    private val updatedTunnelZoneId = randomUuidProto
    private val nUpdatedTunnelZone = nTunnelZoneFromTxt(s"""
        id { $updatedTunnelZoneId }
        name: "tz1"
        type: VXLAN
        """)
    private val hostMappings: String = s"""
        hosts {
            host_id { $randomUuidProto }
            ip { ${toProto("127.0.0.1")} }
        }
        """
    private val mTunnelZoneBeforeUpdate = mTunnelZoneFromTxt(s"""
        id { $updatedTunnelZoneId }
        name: "tz0"
        type: GRE
        $hostMappings
        """)
    private val mTunnelZoneAfterUpdate = mTunnelZoneFromTxt(s"""
        id { $updatedTunnelZoneId }
        name: "tz1"
        type: VXLAN
        $hostMappings
        """)

    "TunnelZone Update" should "add update MidoNet Tunnel Zone's name and " +
    "type while keeping host-IP mapping" in {
        val midoOps = translator.translate(neutron.Update(nUpdatedTunnelZone))

        midoOps should contain (midonet.Create(mTunnelZoneAfterUpdate))
    }

    private val deletedTunnelZoneId = randomUuidProto

    "TunnelZone Delete" should "delete the Tunnel Zone with/without hosts" in {
        val midoOps = translator.translate(
                neutron.Delete(classOf[NeutronTunnelZone],
                               deletedTunnelZoneId))

        midoOps should contain (midonet.Delete(classOf[TunnelZone],
                                               deletedTunnelZoneId))
    }
}