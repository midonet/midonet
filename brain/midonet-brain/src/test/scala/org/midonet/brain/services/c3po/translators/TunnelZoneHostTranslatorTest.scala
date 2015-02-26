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
import org.midonet.cluster.models.Neutron.{NeutronTunnelZone, TunnelZoneHost}
import org.midonet.cluster.models.Topology.TunnelZone
import org.midonet.cluster.util.IPAddressUtil.toProto
import org.midonet.cluster.util.UUIDUtil.randomUuidProto

/**
 * Tests Tunnel Zone Host Translator.
 */
@RunWith(classOf[JUnitRunner])
class TunnelZoneHostTranslatorTest extends FlatSpec with BeforeAndAfter
                                                    with Matchers {
    protected var storage: ReadOnlyStorage = _
    protected var translator: TunnelZoneHostTranslator = _

    before {
        storage = mock(classOf[ReadOnlyStorage])
        translator = new TunnelZoneHostTranslator(storage)

        when(storage.get(classOf[TunnelZone], tunnelZoneId))
            .thenReturn(Promise.successful(mTunnelZone).future)
        when(storage.get(classOf[TunnelZone], tunnelZoneWith2HostsId))
            .thenReturn(Promise.successful(mTunnelZoneWith2Hosts).future)
        when(storage.get(classOf[TunnelZoneHost], tunnelZoneHostToDeleteId))
            .thenReturn(Promise.successful(nTunnelZoneHostToDelete).future)
    }

    private val tunnelZoneId = randomUuidProto
    private val tunnelZoneHostId = randomUuidProto
    private val hostId = randomUuidProto
    private val hostAddress = toProto("127.0.0.1")

    private val nTunnelZoneHost = nTunnelZoneHostFromTxt(s"""
        id { $tunnelZoneHostId }
        tunnel_zone_id { $tunnelZoneId }
        host_id { $hostId }
        ip_address: { $hostAddress }
        """)

    private val mTunnelZoneTxt = s"""
        id { $tunnelZoneId }
        name: "tz0"
        type: GRE
        """
    private val mTunnelZoneWithHostTxt = s"""
        $mTunnelZoneTxt
        hosts {
            host_id { $hostId }
            ip { $hostAddress }
        }
        host_ids { $hostId }
        """
    private val mTunnelZone = mTunnelZoneFromTxt(mTunnelZoneTxt)
    private val mTunnelZoneWithHost =
        mTunnelZoneFromTxt(mTunnelZoneWithHostTxt)

    "TunnelZoneHost Create" should "Update the corresponding TunnelZone " +
    "with a corresponding host-IP address mapping." in {
        val midoOps = translator.translate(neutron.Create(nTunnelZoneHost))

        midoOps should contain (midonet.Update(mTunnelZoneWithHost))
    }

    private val tunnelZoneWith2HostsId = randomUuidProto
    private val tunnelZoneHostToDeleteId = randomUuidProto
    private val nTunnelZoneHostToDelete = nTunnelZoneHostFromTxt(s"""
        id { $tunnelZoneHostToDeleteId }
        tunnel_zone_id { $tunnelZoneWith2HostsId }
        host_id { $hostId }
        ip_address: { $hostAddress }
        """)

    private val anotherHostId = randomUuidProto
    private val mTunnelZoneAfterDeleteTxt = s"""
        id { $tunnelZoneWith2HostsId }
        name: "tz0"
        type: GRE
        hosts {
            host_id { $anotherHostId }
            ip { ${toProto("127.0.0.2")} }
        }
        """
    private val mTunnelZoneWith2HostsTxt = s"""
        $mTunnelZoneAfterDeleteTxt
        hosts {
            host_id { $hostId }
            ip { $hostAddress }
        }
        """
    private val mTunnelZoneWith2Hosts =
        mTunnelZoneFromTxt(mTunnelZoneWith2HostsTxt +
                           s"""host_ids { $anotherHostId }
                               host_ids { $hostId }
                            """)
    private val mTunnelZoneAfterDelete =
        mTunnelZoneFromTxt(mTunnelZoneAfterDeleteTxt +
                           s"""host_ids { $anotherHostId }
                            """)

    "TunnelZoneHost Delete" should "Update the corresponding TunnelZone " +
    "with a corresponding host-IP address mapping removed." in {
        val midoOps = translator.translate(
                neutron.Delete(classOf[TunnelZoneHost],
                               tunnelZoneHostToDeleteId))

        midoOps should contain (midonet.Update(mTunnelZoneAfterDelete))
    }

    "TunnelZoneHost Update" should "throw TranslationException as Update " +
    "is not supported." in {
        intercept[TranslationException] {
            translator.translate(neutron.Update(nTunnelZoneHost))
        }
    }
}