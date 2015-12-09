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

package org.midonet.cluster.services.c3po.translators

import scala.concurrent.Promise

import org.junit.runner.RunWith
import org.mockito.Mockito.when
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.models.ModelsUtil._
import org.midonet.cluster.models.Neutron.{AgentMembership, NeutronConfig}
import org.midonet.cluster.services.c3po.C3POStorageManager.{Create, Delete, Update}
import org.midonet.cluster.util.IPAddressUtil.toProto
import org.midonet.cluster.util.UUIDUtil.randomUuidProto

/**
 * Tests Tunnel Zone Host Translator.
 */
@RunWith(classOf[JUnitRunner])
class AgentMembershipTranslatorTest extends TranslatorTestBase {
    protected var translator: AgentMembershipTranslator = _

    before {
        initMockStorage()
        translator = new AgentMembershipTranslator(storage)
        when(storage.getAll(classOf[NeutronConfig]))
            .thenReturn(Promise.successful(Seq(nConfig)).future)
    }

    private val tunnelZoneId = randomUuidProto
    private val hostId = randomUuidProto
    private val hostAddress = toProto("127.0.0.1")

    private val nConfig = nConfigFromTxt(s"""
        id { $tunnelZoneId }
        tunnel_protocol: GRE
        """)

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
        host_ids { $hostId }
        """

    private val mTunnelZone = mTunnelZoneFromTxt(mTunnelZoneTxt)
    private val mTunnelZoneWithHost =
        mTunnelZoneFromTxt(mTunnelZoneWithHostTxt)

    "TunnelZoneHost Create" should "Update the corresponding TunnelZone " +
    "with a corresponding host-IP address mapping." in {
        bind(tunnelZoneId, mTunnelZone)
        val midoOps = translator.translate(Create(nAgentMembership))

        midoOps should contain (Update(mTunnelZoneWithHost))
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
        mTunnelZoneFromTxt(mTunnelZoneWith2HostsTxt + s"""
            host_ids { $anotherHostId }
            host_ids { $hostId }
        """)
    private val mTunnelZoneAfterDelete =
        mTunnelZoneFromTxt(mTunnelZoneAfterDeleteTxt + s"""
            host_ids { $anotherHostId }
        """)

    "TunnelZoneHost Delete" should "Update the corresponding TunnelZone " +
    "with a corresponding host-IP address mapping removed." in {
        bind(hostId, nAgentMembershipToDelete)
        bind(tunnelZoneId, mTunnelZoneWith2Hosts)

        val midoOps = translator.translate(
                Delete(classOf[AgentMembership], hostId))

        midoOps should contain (Update(mTunnelZoneAfterDelete))
    }

    "TunnelZoneHost Update" should "throw TranslationException as Update " +
    "is not supported." in {
        intercept[TranslationException] {
            translator.translate(Update(nAgentMembership))
        }
    }
}
