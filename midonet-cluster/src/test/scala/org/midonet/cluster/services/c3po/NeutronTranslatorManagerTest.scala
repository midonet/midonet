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

import scala.reflect.ClassTag

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
import org.midonet.cluster.util.SequenceDispenser

@RunWith(classOf[JUnitRunner])
class NeutronTranslatorManagerTest extends FlatSpec
                                           with BeforeAndAfter
                                           with Matchers
                                           with GivenWhenThen {

    class MockTranslator[T <: Message](create: => OperationList = List(),
                                       update: => OperationList = List(),
                                       delete: => OperationList = List())
                                      (implicit ct: ClassTag[T])
        extends Translator[T]()(ct) {
        override def storage = null
        override def translateCreate(tx: Transaction, nm: T): OperationList =
            create
        override def translateUpdate(tx: Transaction, nm: T): OperationList =
            update
        override def translateDelete(tx: Transaction, nm: T): OperationList =
            delete
    }

    private var config: ClusterConfig = _
    private var backend: MidonetBackend = _
    private var storage: Storage = _
    private var transaction: Transaction = _
    private var sequenceDispenser: SequenceDispenser = _

    before {
        config = new ClusterConfig(ConfigFactory.parseString(""))
        backend = Mockito.mock(classOf[MidonetBackend])
        storage = Mockito.mock(classOf[Storage])
        transaction = Mockito.mock(classOf[Transaction])
        sequenceDispenser = Mockito.mock(classOf[SequenceDispenser])

        Mockito.when(backend.store).thenReturn(storage)
        Mockito.when(storage.transaction()).thenReturn(transaction)
    }

    "Manager" should "register default translators" in {
        Given("A translator manager")
        val manager = new NeutronTranslatorManager(config, backend,
                                                   sequenceDispenser) {
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
        val translator = new MockTranslator()

        And("A translator manager")
        val manager = new NeutronTranslatorManager(config, backend,
                                                   sequenceDispenser) {
            protected override def translatorOf(clazz: Class[_])
            : Option[Translator[_]] = {
                Some(translator)
            }
        }

        When("Translating a create")
        manager.translate(transaction, Create(message))

        Then("The manager should add the operation to the transaction")
        Mockito.verify(transaction).create(message)

        When("Translating an update")
        manager.translate(transaction, Update(message))

        Then("The manager should add the operation to the transaction")
        Mockito.verify(transaction).update(message, null)

        When("Translating a delete")
        manager.translate(transaction, Delete(classOf[Message], message))

        Then("The manager should add the operation to the transaction")
        Mockito.verify(transaction).delete(classOf[Message], message, ignoresNeo = true)
    }

    "Manager" should "also handle node operations" in {
        Given("A translator")
        val message = Commons.UUID.newBuilder().setMsb(0L).setLsb(0L).build()
        val translator = new MockTranslator()

        And("A translator manager")
        val manager = new NeutronTranslatorManager(config, backend,
                                                   sequenceDispenser) {
            protected override def translatorOf(clazz: Class[_])
            : Option[Translator[_]] = {
                Some(translator)
            }
        }

        When("Translating a create")
        manager.translate(transaction, Create(message))

        Then("The manager should add the operation to the transaction")
        Mockito.verify(transaction).create(message)
    }

    "Manager" should "throw an exception if class unknown" in {
        Given("A translator manager")
        val manager = new NeutronTranslatorManager(config, backend,
                                                   sequenceDispenser)

        When("Translating an operation for an unknown object class")
        val message = Commons.UUID.newBuilder().setMsb(0L).setLsb(0L).build()

        Then("The manager should throw an exception")
        intercept[TranslationException] {
            manager.translate(transaction, Create(message))
        }
    }

}
