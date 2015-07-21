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

import org.midonet.cluster.state.LocalPortActive // XXX is this right event to watch?

import org.midonet.midolman.logging.ActorLogWithoutPath
import org.midonet.midolman.Referenceable
import org.midonet.util.concurrent.ReactiveActor

object MetadataServiceManagerActor extends Referenceable {
    override val Name = "MetadataServiceManager"
}

class MetadataServiceManagerActor extends ReactiveActor[LocalPortActive]
                                  with ActorLogWithoutPath {
    import context.system

    @Inject
    val backend: MidonetBackend = null

    override def preStart() {
        super.preStart()
        MetadataProxy.start(backend.store)
//      stateStorage.localPortActiveObservable.subscribe(this)
    }

    override def receive = {
        case LocalPortActive(portID, true) =>
            log.info(s"port $portID became active")

        case LocalPortActive(portID, false) =>
            log.info(s"port $portID became inactive")

        case _ => log.error("Unknown message.")
    }
}
