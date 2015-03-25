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
import org.mockito.Mockito.{doThrow, mock, when}
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfter, FlatSpec, Matchers}

import org.midonet.brain.services.c3po.{midonet, neutron}
import org.midonet.cluster.data.storage.{NotFoundException, ReadOnlyStorage}
import org.midonet.cluster.models.Commons.UUID
import org.midonet.cluster.models.ModelsUtil._
import org.midonet.cluster.models.Neutron.PortBinding
import org.midonet.cluster.models.Topology.{Host, Port}
import org.midonet.cluster.util.UUIDUtil.randomUuidProto
import org.midonet.cluster.util.UUIDUtil.fromProto

/**
 * Tests port binding translation.
 */
@RunWith(classOf[JUnitRunner])
class PortBindingTranslatorTest extends FlatSpec with BeforeAndAfter
                                                 with Matchers {

    protected var storage: ReadOnlyStorage = _
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
            port_bindings {
                port_id { $portXOnHost2Id }
                interface_name: "$host2InterfaceA"
            }
            port_bindings {
                port_id { $portYOnHost2Id }
                interface_name: "$host2InterfaceB"
            }
        """)

    private def portWithNoHost(portId: UUID) = mPortFromTxt(s"""
            id { $portId }
         """)
    private def portWithHost(portId: UUID, hostId: UUID, iface_name: String) =
        mPortFromTxt(s"""
            id { $portId }
            host_id { $hostId }
            interface_name: "$iface_name"
        """.stripMargin)

    private def setUpPortGet(portId: UUID, hostId: UUID, iface_name: String)
    : Unit = {
        val port = if (hostId == null) portWithNoHost(portId)
                   else portWithHost(portId, hostId, iface_name)
        when(storage.get(classOf[Port], portId))
            .thenReturn(Promise.successful(port).future)
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
        storage = mock(classOf[ReadOnlyStorage])
        translator = new PortBindingTranslator(storage)

        when(storage.get(classOf[Host], host1NoBindingsId))
            .thenReturn(Promise.successful(host1).future)
        when(storage.get(classOf[Host], host2With2BindingsId))
            .thenReturn(Promise.successful(host2With2Bindings).future)
        doThrow(new NotFoundException(classOf[Host], hostThatDoesntExist))
            .when(storage).get(classOf[Host], hostThatDoesntExist)
        when(storage.get(classOf[PortBinding], bindingHost2PortYInterfaceBId))
            .thenReturn(Promise.successful(bindingHost2PortYInterfaceB).future)
        when(storage.exists(classOf[Port], newPortId))
            .thenReturn(Promise.successful(true).future)
        when(storage.exists(classOf[Port], portThatDoesntExist))
            .thenReturn(Promise.successful(false).future)
    }

    "Port binding" should "add a new PortToInterface entry" in {
        setUpPortGet(newPortId, null, null)

        val midoOps = translator.translate(neutron.Create(binding1))

        val host1WithPort1Bound = mHostFromTxt(s"""
            id { $host1NoBindingsId }
            port_bindings {
                port_id { $newPortId }
                interface_name: "$newIface"
            }
            """)

        midoOps should contain inOrderOnly(
            midonet.UpdateWithOwner(host1WithPort1Bound,
                                    fromProto(host1NoBindingsId).toString),
            midonet.Update(portWithHost(
                    newPortId, host1NoBindingsId, newIface)))
    }

    it should "add a new mapping at the end of the existing mappings" in {
        setUpPortGet(newPortId, null, null)
        val midoOps = translator.translate(
                neutron.Create(bindingHost2NewPortInterface))

        val host2WithNewInterfacePort = mHostFromTxt(s"""
            id { $host2With2BindingsId }
            port_bindings {
                port_id { $portXOnHost2Id }
                interface_name: "$host2InterfaceA"
            }
            port_bindings {
                port_id { $portYOnHost2Id }
                interface_name: "$host2InterfaceB"
            }
            port_bindings {
                port_id { $newPortId }
                interface_name: "$newIface"
            }
            """)
        midoOps should contain inOrderOnly(
            midonet.UpdateWithOwner(host2WithNewInterfacePort,
                                    fromProto(host2With2BindingsId).toString),
            midonet.Update(portWithHost(
                    newPortId, host2With2BindingsId, newIface)))
    }

    "Port binding to a non-existing port" should "throw an exception" in {
        intercept[TranslationException] {
            val midoOps =
                translator.translate(neutron.Create(bindingToNonExistingPort))
        }
    }

    "Port binding to a non-existing host" should "throw an exception" in {
        intercept[TranslationException] {
            val midoOps =
                translator.translate(neutron.Create(bindingToNonExistingHost))
        }
    }

    "Port binding to an already bound port" should "throw an exception" in {
        intercept[TranslationException] {
            val midoOps = translator.translate(
                neutron.Create(bindingHost2WithSameInterface))
        }
    }

    "Attempting to update a port binding" should "throw an exception" in {
        intercept[TranslationException] {
            val midoOps = translator.translate(
                neutron.Update(bindingHost2NewPortInterface))
        }
    }

    "Port binding delete" should "remove port/interface mapping from Host" in {
        setUpPortGet(portYOnHost2Id, host2With2BindingsId, host2InterfaceA)

        val midoOps = translator.translate(neutron.Delete(
                classOf[PortBinding], bindingHost2PortYInterfaceBId))

        val host2With1Binding = mHostFromTxt(s"""
            id { $host2With2BindingsId }
            port_bindings {
                port_id { $portXOnHost2Id }
                interface_name: "$host2InterfaceA"
            }
        """)

        midoOps should contain inOrderOnly(
            midonet.UpdateWithOwner(host2With1Binding,
                                    fromProto(host2With2BindingsId).toString),
            midonet.Update(portWithNoHost(portYOnHost2Id)))
    }
}