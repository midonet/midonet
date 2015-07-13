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

package org.midonet.midolman.openstack.metadata

import akka.actor._
import com.google.inject.Inject

import org.midonet.cluster.services.MidonetBackend
import org.midonet.midolman.topology.LocalPortActive
import org.midonet.midolman.logging.ActorLogWithoutPath
import org.midonet.midolman.Referenceable
import org.midonet.util.concurrent.SubscriberActor

object MetadataServiceManagerActor extends Referenceable {
    override val Name = "MetadataServiceManager"
}

class MetadataServiceManagerActor extends SubscriberActor
                                  with ActorLogWithoutPath {
    import context.system

    @Inject
    val backend: MidonetBackend = null
    var zk: MetadataZkClient = null

    val remoteAddr = "169.254.169.254"  // XXX

    override def subscribedClasses = Seq(classOf[LocalPortActive])

    override def preStart() {
        super.preStart
        zk = new MetadataZkClient(backend.store)
        MetadataProxy.start
    }

    override def receive = {
        case LocalPortActive(portId, true) =>
            log.info(s"Metadata: port $portId became active")
            zk getComputePortInfo portId match {
                case Some(info) =>
                    MetadataAddressMap put (remoteAddr, info)
                case _ =>
                    log.info(s"Non-neutron port? ${portId}")
            }

        case LocalPortActive(portId, false) =>
            log.info(s"Metadata: port $portId became inactive")
            zk getComputePortInfo portId match {
                case Some(info) =>
                    MetadataAddressMap remove remoteAddr
                case _ =>
                    log.info(s"Non-neutron port? ${portId}")
            }

        case _ => log.error("Unknown message.")
    }
}
