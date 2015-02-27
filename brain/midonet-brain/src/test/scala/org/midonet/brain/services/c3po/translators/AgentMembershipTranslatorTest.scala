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
import org.midonet.cluster.models.Neutron.{NeutronTunnelZone, AgentMembership}
import org.midonet.cluster.models.Topology.TunnelZone
import org.midonet.cluster.util.IPAddressUtil.toProto
import org.midonet.cluster.util.UUIDUtil.randomUuidProto

/**
 * Tests Tunnel Zone Host Translator.
 */
@RunWith(classOf[JUnitRunner])
class AgentMembershipTranslatorTest extends FlatSpec with BeforeAndAfter
                                                     with Matchers {
    protected var storage: ReadOnlyStorage = _
    protected var translator: AgentMembershipTranslator = _

    before {
        storage = mock(classOf[ReadOnlyStorage])
        translator = new AgentMembershipTranslator(storage)
    }

    private val tunnelZoneId = randomUuidProto
    private val tunnelZoneHostId = randomUuidProto
    private val hostId = randomUuidProto
    private val hostAddress = toProto("127.0.0.1")

    private val nAgentMembership = nAgentMembershipFromTxt(s"""
        id { $hostId }
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
        """
    private val mTunnelZone = mTunnelZoneFromTxt(mTunnelZoneTxt)
    private val mTunnelZoneWithHost =
        mTunnelZoneFromTxt(mTunnelZoneWithHostTxt)

    "TunnelZoneHost Create" should "Update the corresponding TunnelZone " +
    "with a corresponding host-IP address mapping." in {
        when(storage.getAll(classOf[TunnelZone]))
            .thenReturn(Promise.successful(Seq(mTunnelZone)).future)
        when(storage.get(classOf[TunnelZone], tunnelZoneId))
            .thenReturn(Promise.successful(mTunnelZone).future)
        val midoOps = translator.translate(neutron.Create(nAgentMembership))

        midoOps should contain (midonet.Update(mTunnelZoneWithHost))
    }

    private val nAgentMembershipToDelete = nAgentMembershipFromTxt(s"""
        id { $hostId }
        ip_address: { $hostAddress }
        """)

    private val anotherHostId = randomUuidProto
    private val mTunnelZoneAfterDeleteTxt = s"""
        id { $tunnelZoneId }
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
        mTunnelZoneFromTxt(mTunnelZoneWith2HostsTxt)
    private val mTunnelZoneAfterDelete =
        mTunnelZoneFromTxt(mTunnelZoneAfterDeleteTxt)

    "TunnelZoneHost Delete" should "Update the corresponding TunnelZone " +
    "with a corresponding host-IP address mapping removed." in {
        when(storage.getAll(classOf[TunnelZone]))
            .thenReturn(Promise.successful(Seq(mTunnelZoneWith2Hosts)).future)
        when(storage.get(classOf[AgentMembership], hostId))
            .thenReturn(Promise.successful(nAgentMembershipToDelete).future)

        val midoOps = translator.translate(
                neutron.Delete(classOf[AgentMembership], hostId))

        midoOps should contain (midonet.Update(mTunnelZoneAfterDelete))
    }

    "TunnelZoneHost Update" should "throw TranslationException as Update " +
    "is not supported." in {
        intercept[TranslationException] {
            translator.translate(neutron.Update(nAgentMembership))
        }
    }
}