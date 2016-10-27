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

import org.midonet.cluster.models.Neutron.NeutronLoggingResource
import org.midonet.cluster.models.Topology.LoggingResource
import org.midonet.cluster.services.c3po.NeutronTranslatorManager.{Create, Delete, Update}
import org.midonet.cluster.util.UUIDUtil

@RunWith(classOf[JUnitRunner])
class LoggingResourceTranslatorTest extends TranslatorTestBase with GivenWhenThen {

    val translator = new LoggingResourceTranslator()

    "Logging resource create" should "throw an exception" in {
        Given("A mock storage")
        initMockStorage()

        And("A logging resource")
        val loggingResource = NeutronLoggingResource.newBuilder().build()

        Then("Translating a create operation throws an exception")
        val e = intercept[TranslationException] {
            translator.translate(transaction, Create(loggingResource))
        }
        e.getCause.isInstanceOf[UnsupportedOperationException] shouldBe true
    }

    "Logging resource update" should "do nothing for non-existing resource" in {
        Given("A mock storage")
        initMockStorage()

        And("A logging resource")
        val loggingResource = NeutronLoggingResource.newBuilder()
            .setId(UUIDUtil.randomUuidProto).build()

        And("The resource exists does not exist storage")
        Mockito.when(transaction.exists(classOf[LoggingResource],
                                        loggingResource.getId)).thenReturn(false)

        When("Translating an update operation")
        translator.translate(transaction, Update(loggingResource))

        Then("The result is no operation")
        midoOps shouldBe empty
    }

    "Logging resource update" should "modify enabled of existing resource" in {
        Given("A mock storage")
        initMockStorage()

        And("An initial logging resource")
        val mLoggingResource = LoggingResource.newBuilder()
            .setId(UUIDUtil.randomUuidProto)
            .setEnabled(false)
            .build()

        And("An updated logging resource")
        val nLoggingResource = NeutronLoggingResource.newBuilder()
            .setId(mLoggingResource.getId)
            .setName("some name")
            .setEnabled(true)
            .build()

        And("The resource exists in storage")
        Mockito.when(transaction.exists(classOf[LoggingResource],
                                        mLoggingResource.getId)).thenReturn(true)
        Mockito.when(transaction.get(classOf[LoggingResource],
                                     mLoggingResource.getId))
               .thenReturn(mLoggingResource)

        When("Translating an update operation")
        translator.translate(transaction, Update(nLoggingResource))

        Then("The result is an update of the initial logging resource")
        midoOps shouldBe List(Update(mLoggingResource.toBuilder
                                         .setEnabled(true).build()))
    }

    "Logging resource delete" should "delete the existing resource" in {
        Given("A mock storage")
        initMockStorage()

        val id = UUIDUtil.randomUuidProto

        When("Translating a delete operation")
        translator.translate(transaction,
                             Delete(classOf[NeutronLoggingResource], id))

        Then("The result is a delete of the MidoNet logging resource")
        midoOps shouldBe List(Delete(classOf[LoggingResource], id))
    }

}
