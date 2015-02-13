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
import org.scalatest.{BeforeAndAfter, FlatSpec, Matchers}
import org.scalatest.junit.JUnitRunner
import org.midonet.brain.services.c3po.{midonet, neutron}
import org.midonet.cluster.data.storage.{NotFoundException, ReadOnlyStorage}
import org.midonet.cluster.models.Commons.UUID
import org.midonet.cluster.models.ModelsUtil._
import org.midonet.cluster.models.Neutron.PortBinding
import org.midonet.cluster.models.Topology.{Host, Port}
import org.midonet.cluster.util.UUIDUtil
import org.midonet.cluster.util.UUIDUtil.randomUuidProto
import org.midonet.cluster.models.Neutron.PortBinding

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
            port_interface_mapping {
                port_id { $portXOnHost2Id }
                interface_name: "$host2InterfaceA"
            }
            port_interface_mapping {
                port_id { $portYOnHost2Id }
                interface_name: "$host2InterfaceB"
            }
        """)

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
        val midoOps = translator.translate(neutron.Create(binding1))

        val host1WithPort1Bound = mHostFromTxt(s"""
            id { $host1NoBindingsId }
            port_interface_mapping {
                port_id { $newPortId }
                interface_name: "$newIface"
            }
            """)
        midoOps should contain (midonet.Update(host1WithPort1Bound))
    }

    it should "add a new mapping at the end of the existing mappings" in {
        val midoOps = translator.translate(
                neutron.Create(bindingHost2NewPortInterface))

        val host2WithNewInterfacePort = mHostFromTxt(s"""
            id { $host2With2BindingsId }
            port_interface_mapping {
                port_id { $portXOnHost2Id }
                interface_name: "$host2InterfaceA"
            }
            port_interface_mapping {
                port_id { $portYOnHost2Id }
                interface_name: "$host2InterfaceB"
            }
            port_interface_mapping {
                port_id { $newPortId }
                interface_name: "$newIface"
            }
            """)
        midoOps should contain (midonet.Update(host2WithNewInterfacePort))
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

    "Port binding delete" should "remove port/interface mapping from Host" in {
        val midoOps = translator.translate(neutron.Delete(
                classOf[PortBinding], bindingHost2PortYInterfaceBId))

        val host2With1Binding = mHostFromTxt(s"""
            id { $host2With2BindingsId }
            port_interface_mapping {
                port_id { $portXOnHost2Id }
                interface_name: "$host2InterfaceA"
            }
        """)
        midoOps should contain (midonet.Update(host2With1Binding))
    }
}