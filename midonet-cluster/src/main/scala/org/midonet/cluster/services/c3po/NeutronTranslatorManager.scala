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

import org.midonet.cluster.ClusterConfig
import org.midonet.cluster.data.storage._
import org.midonet.cluster.models.Commons.UUID
import org.midonet.cluster.models.Neutron._
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.services.c3po.NeutronTranslatorManager.{Create, Delete, Operation, Update}
import org.midonet.cluster.services.c3po.translators._
import org.midonet.cluster.util.SequenceDispenser

object NeutronTranslatorManager {

    /**
      * A generic operation on a model.
      */
    trait Operation[T <: Message] {
        def apply(tx: Transaction): Unit
        def opType: OpType.OpType
    }

    case class Create[T <: Message](model: T) extends Operation[T] {
        override val opType = OpType.Create
        override def apply(tx: Transaction): Unit = {
            tx.create(model)
        }
    }

    case class Update[T <: Message](model: T,
                                    validator: UpdateValidator[T] = null)
        extends Operation[T] {
        override val opType = OpType.Update
        override def apply(tx: Transaction): Unit = {
            tx.update(model, validator.asInstanceOf[UpdateValidator[Object]])
        }
    }

    case class Delete[T <: Message](clazz: Class[T], id: UUID)
        extends Operation[T] {
        override val opType = OpType.Delete
        override def apply(tx: Transaction): Unit = {
            // Neutron deletion semantics is delete-if-exists by default
            // and no-op if the object doesn't exist. Revisit if we need to make
            // this configurable.
            tx.delete(clazz, id, ignoresNeo = true)
        }
    }

    case class CreateNode(path: String, value: String = null)
        extends Operation[Nothing] {
        override val opType = OpType.CreateNode
        override def apply(tx: Transaction): Unit = {
            tx.createNode(path, value)
        }
    }

    case class UpdateNode(path: String, value: String)
        extends Operation[Nothing] {
        override val opType = OpType.UpdateNode
        override def apply(tx: Transaction): Unit = {
            tx.updateNode(path, value)
        }
    }

    case class DeleteNode(path: String) extends Operation[Nothing] {
        override def opType = OpType.DeleteNode
        override def apply(tx: Transaction): Unit = {
            tx.deleteNode(path)
        }
    }

}

/**
  * A manager for Neutron translation operations. This class exposes a single
  * `translate` method that converts a given Neutron operation to a sequence of
  * NSDB operations and applies them to a provided ZOOM transaction.
  *
  * The class defines a default set of translators mapped to specific Neutron
  * object classes. Derived classes may override this list or include additional
  * translators.
  */
class NeutronTranslatorManager(config: ClusterConfig,
                               backend: MidonetBackend,
                               sequenceDispenser: SequenceDispenser) {

    private val translators = Map[Class[_], Translator[_]](
        classOf[AgentMembership] -> new AgentMembershipTranslator(store),
        classOf[FirewallLog] -> new FirewallLogTranslator(store),
        classOf[FloatingIp] -> new FloatingIpTranslator(store, stateTableStore),
        classOf[GatewayDevice] -> new GatewayDeviceTranslator(store, stateTableStore),
        classOf[IPSecSiteConnection] -> new IPSecSiteConnectionTranslator(store),
        classOf[L2GatewayConnection] -> new L2GatewayConnectionTranslator(store, stateTableStore),
        classOf[NeutronBgpPeer] -> new BgpPeerTranslator(store, stateTableStore, sequenceDispenser),
        classOf[NeutronBgpSpeaker] -> new BgpSpeakerTranslator(store, stateTableStore),
        classOf[NeutronConfig] -> new ConfigTranslator(store),
        classOf[NeutronFirewall] -> new FirewallTranslator(store),
        classOf[NeutronHealthMonitor] -> new HealthMonitorTranslator(store),
        classOf[NeutronLoadBalancerPool] -> new LoadBalancerPoolTranslator(store),
        classOf[NeutronLoggingResource] -> new LoggingResourceTranslator(store),
        classOf[NeutronLoadBalancerPoolMember] -> new LoadBalancerPoolMemberTranslator(store),
        classOf[NeutronNetwork] -> new NetworkTranslator(store),
        classOf[NeutronRouter] -> new RouterTranslator(store, stateTableStore, config),
        classOf[NeutronRouterInterface] -> new RouterInterfaceTranslator(store, sequenceDispenser, config),
        classOf[NeutronSubnet] -> new SubnetTranslator(store),
        classOf[NeutronPort] -> new PortTranslator(store, stateTableStore, sequenceDispenser),
        classOf[NeutronVIP] -> new VipTranslator(store, stateTableStore),
        classOf[PortBinding] -> new PortBindingTranslator(store),
        classOf[RemoteMacEntry] -> new RemoteMacEntryTranslator(store, stateTableStore),
        classOf[SecurityGroup] -> new SecurityGroupTranslator(store),
        classOf[SecurityGroupRule] -> new SecurityGroupRuleTranslator(store),
        classOf[TapService] -> new TapServiceTranslator(store),
        classOf[TapFlow] -> new TapFlowTranslator(store),
        classOf[VpnService] -> new VpnServiceTranslator(store, sequenceDispenser)
    )

    private def store: Storage = backend.store
    private def stateTableStore: StateTableStorage = backend.stateTableStore

    /**
      * Translates the specified Neutron operation and appends the translated
      * ZOOM operations to the given transaction.
      */
    @throws[TranslationException]
    def translate[T <: Message](tx: Transaction, op: Operation[T]): Unit = {
        val clazz = op match {
            case Create(model) => model.getClass
            case Update(model, _) => model.getClass
            case Delete(c, _) => c
        }

        translatorOf(clazz).getOrElse({
            throw new TranslationException(op, cause = null,
                                           s"No translator for $clazz")
        })
            .asInstanceOf[Translator[T]]
            .translateOp(op)
            .foreach(_.apply(tx))
    }

    /**
      * Returns a Neutron translator instance for the specified Neutron object
      * class. Derived classes may modify the returned translators by overriding
      * this method.
      */
    protected def translatorOf(clazz: Class[_]): Option[Translator[_]] = {
        translators.get(clazz)
    }

}
