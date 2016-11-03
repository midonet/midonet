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

package org.midonet.cluster.services.c3po

import com.google.protobuf.Message
import com.typesafe.config.ConfigFactory

import org.junit.runner.RunWith
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.mockito.{Mockito, Matchers => MockitoMatchers}
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfter, FlatSpec, GivenWhenThen, Matchers}

import org.midonet.cluster.ClusterConfig
import org.midonet.cluster.data.storage.{Storage, Transaction}
import org.midonet.cluster.models.Commons
import org.midonet.cluster.models.Neutron._
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.services.c3po.NeutronTranslatorManager._
import org.midonet.cluster.services.c3po.translators._

@RunWith(classOf[JUnitRunner])
class NeutronTranslatorManagerTest extends FlatSpec
                                           with BeforeAndAfter
                                           with Matchers
                                           with GivenWhenThen {

    private var config: ClusterConfig = _
    private var backend: MidonetBackend = _
    private var storage: Storage = _
    private var transaction: Transaction = _

    before {
        config = new ClusterConfig(ConfigFactory.parseString(""))
        backend = Mockito.mock(classOf[MidonetBackend])
        storage = Mockito.mock(classOf[Storage])
        transaction = Mockito.mock(classOf[Transaction])

        Mockito.when(backend.store).thenReturn(storage)
        Mockito.when(storage.transaction()).thenReturn(transaction)
    }

    "Manager" should "register default translators" in {
        Given("A translator manager")
        val manager = new NeutronTranslatorManager(config, backend) {
            def get(clazz: Class[_]): Option[Translator[_]] = {
                translatorOf(clazz)
            }
        }

        Then("The manager contains the default translators")
        val classes = Seq(classOf[AgentMembership],
                          classOf[FirewallLog],
                          classOf[FloatingIp],
                          classOf[GatewayDevice],
                          classOf[IPSecSiteConnection],
                          classOf[L2GatewayConnection],
                          classOf[NeutronBgpPeer],
                          classOf[NeutronBgpSpeaker],
                          classOf[NeutronConfig],
                          classOf[NeutronFirewall],
                          classOf[NeutronHealthMonitor],
                          classOf[NeutronLoadBalancerPool],
                          classOf[NeutronLoggingResource],
                          classOf[NeutronLoadBalancerPoolMember],
                          classOf[NeutronNetwork],
                          classOf[NeutronRouter],
                          classOf[NeutronRouterInterface],
                          classOf[NeutronSubnet],
                          classOf[NeutronPort],
                          classOf[NeutronVIP],
                          classOf[PortBinding],
                          classOf[RemoteMacEntry],
                          classOf[SecurityGroup],
                          classOf[SecurityGroupRule],
                          classOf[TapService],
                          classOf[TapFlow],
                          classOf[VpnService])
        classes.foreach {
            manager.get(_) should not be None
        }
    }

    "Manager" should "pass Neutron operations to the translator" in {
        Given("A translator")
        val message = Commons.UUID.newBuilder().setMsb(0L).setLsb(0L).build()
        val translator = Mockito.mock(classOf[Translator[Message]])
        Mockito.when(translator.translateOp(MockitoMatchers.any(),
                                            MockitoMatchers.any()))
               .thenAnswer(new Answer[OperationList] {
                   override def answer(invocation: InvocationOnMock): OperationList = {
                       List(invocation.getArguments.apply(1)).asInstanceOf[OperationList]
                   }
               })

        And("A translator manager")
        val manager = new NeutronTranslatorManager(config, backend) {
            protected override def translatorOf(clazz: Class[_])
            : Option[Translator[_]] = {
                Some(translator)
            }
        }

        When("Translating a create")
        manager.translate(transaction, Create(message))

        Then("The manager should call the translator")
        Mockito.verify(translator).translateOp(transaction, Create(message))

        And("The returned operations are added to the transaction")
        Mockito.verify(transaction).create(message)

        When("Translating an update")
        manager.translate(transaction, Update(message))

        Then("The manager should call the translator")
        Mockito.verify(translator).translateOp(transaction, Update(message))

        And("The returned operations are added to the transaction")
        Mockito.verify(transaction).update(message, validator = null)

        When("Translating a delete")
        manager.translate(transaction, Delete(classOf[Message], message))

        Then("The manager should call the translator")
        Mockito.verify(translator).translateOp(transaction,
                                               Delete(classOf[Message], message))

        And("The returned operations are added to the transaction")
        Mockito.verify(transaction).delete(classOf[Message], message,
                                           ignoresNeo = true)
    }

    "Manager" should "also handle node operations" in {
        Given("A translator")
        val message = Commons.UUID.newBuilder().setMsb(0L).setLsb(0L).build()
        val translator = Mockito.mock(classOf[Translator[Message]])
        Mockito.when(translator.translateOp(MockitoMatchers.any(),
                                            MockitoMatchers.any()))
            .thenAnswer(new Answer[OperationList] {
                override def answer(invocation: InvocationOnMock): OperationList = {
                    List(CreateNode("path0", "value0"),
                         UpdateNode("path1", "value1"),
                         DeleteNode("path2"))
                }
            })

        And("A translator manager")
        val manager = new NeutronTranslatorManager(config, backend) {
            protected override def translatorOf(clazz: Class[_])
            : Option[Translator[_]] = {
                Some(translator)
            }
        }

        When("Translating a create")
        manager.translate(transaction, Create(message))

        Then("The manager should call the translator")
        Mockito.verify(translator).translateOp(transaction, Create(message))

        And("The returned operations are added to the transaction")
        Mockito.verify(transaction).createNode("path0", "value0")
        Mockito.verify(transaction).updateNode("path1", "value1")
        Mockito.verify(transaction).deleteNode("path2")
    }

    "Manager" should "throw an exception if class unknown" in {
        Given("A translator manager")
        val manager = new NeutronTranslatorManager(config, backend)

        When("Translating an operation for an unknown object class")
        val message = Commons.UUID.newBuilder().setMsb(0L).setLsb(0L).build()

        Then("The manager should throw an exception")
        intercept[TranslationException] {
            manager.translate(transaction, Create(message))
        }
    }

}
