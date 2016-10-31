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

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.models.Commons.UUID
import org.midonet.cluster.models.ModelsUtil._
import org.midonet.cluster.models.Neutron.PortBinding
import org.midonet.cluster.models.Topology.{Host, Port}
import org.midonet.cluster.services.c3po.NeutronTranslatorManager._
import org.midonet.cluster.util.UUIDUtil.{randomUuidProto, toProtoFromProtoStr}

/**
 * Tests port binding translation.
 */
@RunWith(classOf[JUnitRunner])
class PortBindingTranslatorTest extends TranslatorTestBase {

    protected var translator: PortBindingTranslator = _

    protected val newPortId = randomUuidProto
    protected val portThatDoesntExist = randomUuidProto  // Non-existing
    protected val portXOnHost2Id = randomUuidProto
    protected val portYOnHost2Id = randomUuidProto

    protected val host1NoBindingsId = randomUuidProto
    protected val host2With2BindingsId = randomUuidProto
    protected val hostThatDoesntExist = randomUuidProto

    protected val newIface = "en0"
    protected val host2InterfaceA = "in-a"
    protected val host2InterfaceB = "in-b"

    private val host1 = mHostFromTxt(s"""
            id { $host1NoBindingsId }
        """)
    private val host2With2Bindings = mHostFromTxt(s"""
            id { $host2With2BindingsId }
            port_ids { $portXOnHost2Id }
            port_ids { $portYOnHost2Id }
        """)

    private def portWithNoHost(portId: UUID) = mPortFromTxt(s"""
            id { $portId }
         """)
    private def portWithHost(portId: UUID, hostId: UUID, iface_name: String) =
        mPortFromTxt(s"""
            id { $portId }
            host_id { $hostId }
            interface_name: "$iface_name"
        """)

    private def setUpPortGet(portId: UUID, hostId: UUID, ifaceName: String)
    : Unit = {
        val port = if (hostId == null) portWithNoHost(portId)
                   else portWithHost(portId, hostId, ifaceName)
        bind(portId, port)
    }

    private val binding1 = portBinding(host1NoBindingsId, newIface, newPortId)
    private val bindingToNonExistingPort =
        portBinding(host1NoBindingsId, newIface, portThatDoesntExist)
    private val bindingToNonExistingHost =
        portBinding(hostThatDoesntExist, newIface, newPortId)

    private val bindingHost2NewPortInterfaceId = randomUuidProto
    private val bindingHost2NewPortInterface =
        portBinding(host2With2BindingsId, newIface, newPortId,
                    bindingHost2NewPortInterfaceId)

    private val bindingHost2WithSameInterface =
        portBinding(host2With2BindingsId, host2InterfaceA, newPortId)

    private val bindingHost2PortYInterfaceBId = randomUuidProto
    private val bindingHost2PortYInterfaceB =
        portBinding(host2With2BindingsId, host2InterfaceB, portYOnHost2Id,
                    bindingHost2PortYInterfaceBId)

    private def portBinding(hostId: UUID, interface: String, portId: UUID,
                            bindingId: UUID = randomUuidProto) =
        PortBinding.newBuilder().setId(bindingId)
                                .setHostId(hostId)
                                .setInterfaceName(interface)
                                .setPortId(portId).build()

    before {
        initMockStorage()
        translator = new PortBindingTranslator()

        bind(host1NoBindingsId, host1)
        bind(host2With2BindingsId,host2With2Bindings)
        bind(hostThatDoesntExist, null, classOf[Host])
        bind(bindingHost2PortYInterfaceBId, bindingHost2PortYInterfaceB)
        bind(newPortId, Port.getDefaultInstance)
        bind(portThatDoesntExist, null, classOf[Port])
    }

    "Port binding" should "add a new PortToInterface entry" in {
        setUpPortGet(newPortId, null, null)

        translator.translate(transaction, Create(binding1))

        midoOps should contain only
            Update(portWithHost(newPortId, host1NoBindingsId, newIface))
    }

    it should "add a new mapping at the end of the existing mappings" in {
        setUpPortGet(newPortId, null, null)
        translator.translate(transaction,
                                           Create(bindingHost2NewPortInterface))

        midoOps should contain only
            Update(portWithHost(newPortId, host2With2BindingsId, newIface))
    }

    "Port binding to a non-existing port" should "throw an exception" in {
        intercept[TranslationException] {
            translator.translate(transaction, Create(bindingToNonExistingPort))
        }
    }

    "Port binding to an already bound port" should "throw an exception" in {
        // Set up the Port with newPortId bound to some host / interface
        setUpPortGet(newPortId,
                     hostId = toProtoFromProtoStr("msb: 1 lsb: 1"),
                     ifaceName = "bound interface")
        val te = intercept[TranslationException] {
            translator.translate(transaction, Create(binding1))
        }
        te.getCause match {
            case ise: IllegalStateException =>
                ise.getMessage contains("already bound") shouldBe true
            case _ => fail("Expected IllegalStateException.")
        }
    }

    "Attempting to update a port binding" should "throw an exception" in {
        intercept[TranslationException] {
            translator.translate(transaction,
                                 Update(bindingHost2NewPortInterface))
        }
    }

    "Port binding delete" should "remove port/interface mapping from Host" in {
        setUpPortGet(portYOnHost2Id, host2With2BindingsId, host2InterfaceA)

        translator.translate(transaction, Delete(classOf[PortBinding],
                                                 bindingHost2PortYInterfaceBId))

        midoOps should contain only Update(portWithNoHost(portYOnHost2Id))
    }
}