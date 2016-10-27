/*
 * Copyright 2016 Midokura SARL
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
import org.mockito.Mockito
import org.scalatest.GivenWhenThen
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.models.Neutron.NeutronConfig
import org.midonet.cluster.models.Neutron.NeutronConfig.TunnelProtocol
import org.midonet.cluster.models.Topology.TunnelZone
import org.midonet.cluster.services.c3po.NeutronTranslatorManager.{Create, Delete, Update}
import org.midonet.cluster.util.UUIDUtil

@RunWith(classOf[JUnitRunner])
class ConfigTranslatorTest extends TranslatorTestBase with GivenWhenThen
                                   with TunnelZoneManager {

    "Configuration CREATE" should "create a tunnel zone" in {
        Given("A mock storage")
        initMockStorage()

        And("A configuration translator")
        val translator = new ConfigTranslator()

        And("A configuration")
        val id = UUIDUtil.randomUuidProto
        val config = NeutronConfig.newBuilder()
            .setId(id)
            .setTunnelProtocol(TunnelProtocol.GRE)
            .build()

        And("The tunnel zone does not exist")
        Mockito.when(transaction.exists(classOf[TunnelZone], id))
            .thenReturn(false)

        When("Translating a configuration create")
        translator.translate(transaction, Create(config))

        Then("The translator should create the tunnel zone")
        midoOps should contain only Create(neutronDefaultTunnelZone(config))
    }

    "Configuration CREATE" should "be no op if tunnel zone exists" in {
        Given("A mock storage")
        initMockStorage()

        And("A configuration translator")
        val translator = new ConfigTranslator()

        And("A configuration")
        val id = UUIDUtil.randomUuidProto
        val config = NeutronConfig.newBuilder()
            .setId(id)
            .setTunnelProtocol(TunnelProtocol.GRE)
            .build()

        And("The tunnel zone exists")
        Mockito.when(transaction.exists(classOf[TunnelZone], id))
            .thenReturn(true)

        When("Translating a configuration create")
        translator.translate(transaction, Create(config))

        Then("The translator should not create a tunnel zone")
        midoOps shouldBe empty
    }

    "Configuration UPDATE" should "throw exception" in {
        Given("A mock storage")
        initMockStorage()

        And("A config translator")
        val translator = new ConfigTranslator()

        And("A config")
        val id = UUIDUtil.randomUuidProto
        val config = NeutronConfig.newBuilder()
            .setId(id)
            .setTunnelProtocol(TunnelProtocol.GRE)
            .build()

        Then("Translating a configuration update throws an exception")
        intercept[TranslationException] {
            translator.translate(transaction, Update(config))
        }
    }

    "Configuration DELETE" should "throw exception" in {
        Given("A mock storage")
        initMockStorage()

        And("A configuration translator")
        val translator = new ConfigTranslator()

        And("A configuration identifier")
        val id = UUIDUtil.randomUuidProto

        Then("Translating a configuration delete throws an exception")
        intercept[TranslationException] {
            translator.translate(transaction, Delete(classOf[NeutronConfig], id))
        }

    }

}
