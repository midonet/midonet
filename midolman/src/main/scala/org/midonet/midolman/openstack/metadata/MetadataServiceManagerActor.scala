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

import com.google.inject.Inject

import rx.Subscription

import org.midonet.cluster.services.MidonetBackend
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.logging.ActorLogWithoutPath
import org.midonet.midolman.Referenceable
import org.midonet.midolman.topology.VirtualToPhysicalMapper
import org.midonet.midolman.topology.VirtualToPhysicalMapper.LocalPortActive
import org.midonet.util.concurrent.ReactiveActor

object MetadataServiceManagerActor extends Referenceable {
    override val Name = "MetadataServiceManager"
}

// XXX Should update InstanceInfoMap on fixed_ips update.

class MetadataServiceManagerActor @Inject() (
            private val backend: MidonetBackend,
            private val config: MidolmanConfig,
            private val plumber: Plumber,
            private val datapathInterface: DatapathInterface
        ) extends ReactiveActor[LocalPortActive] with ActorLogWithoutPath {
    import context.system

    var store: StorageClient = null
    var mdInfo: ProxyInfo = null
    private var subscription: Subscription = null

    override def preStart(): Unit = {
        log info "Starting metadata service"
        super.preStart
        subscription = VirtualToPhysicalMapper.portsActive.subscribe(this)
        store = new StorageClient(backend.store)
        mdInfo = datapathInterface.init
        MetadataServiceWorkflow.mdInfo = mdInfo
        Proxy start config
    }

    override def postStop(): Unit = {
        super.postStop()
        if (subscription ne null) {
            subscription.unsubscribe()
            subscription = null
        }
    }

    override def receive = {
        case LocalPortActive(portId, true) =>
            log debug s"Metadata: port $portId became active"
            /*
             * XXX Theoretically, this can race with metadata requests
             * from the corresponding VM.  If we lose the race,
             * the requests will be dropped by handleMetadataEgress.
             * It's unlikely though, because typically VM doesn't
             * use metadata service that early in its boot process.
             */
            store getComputePortInfo portId match {
                case Some(info) =>
                    val remoteAddr = plumber.plumb(info, mdInfo)
                    InstanceInfoMap.put(remoteAddr, portId, info)
                case _ =>
                    log debug s"Non-compute port? ${portId}"
            }

        case LocalPortActive(portId, false) =>
            log debug s"Metadata: port $portId became inactive"
            InstanceInfoMap getByPortId portId match {
                case Some(remoteAddr) =>
                    val Some(info) = InstanceInfoMap getByAddr remoteAddr

                    plumber.unplumb(remoteAddr, info, mdInfo)
                    InstanceInfoMap removeByPortId portId
                case _ =>
                    log debug s"Non-compute port? ${portId}"
            }

        case _ => log error "Unknown message."
    }
}
