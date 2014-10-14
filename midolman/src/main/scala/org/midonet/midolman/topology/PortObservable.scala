/*
 * Copyright 2014 Midokura SARL
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
package org.midonet.midolman.topology

import java.util.UUID

import akka.actor.ActorSystem

import rx.subjects.PublishSubject

import org.midonet.cluster.data.ZoomConvert
import org.midonet.cluster.data.storage.Storage
import org.midonet.cluster.models.Devices
import org.midonet.midolman.topology.devices.Port
import org.midonet.util.functors._

sealed class PortObservable(id: UUID, store: Storage, vt: VirtualTopology)
                           (implicit actorSystem: ActorSystem)
        extends DeviceObservable[Port](id, vt) {

    override def logSource = s"org.midonet.midolman.topology.port-$id"

    protected override def observable = {
        val inStream = PublishSubject.create[Devices.Port]()
        val subscription = store.subscribe(classOf[Devices.Port], id,
                                           inStream)

        inStream
            .map[Port](makeFunc1(
            port => ZoomConvert.fromProto(port, classOf[Port])))
            .doOnUnsubscribe(makeAction0 { subscription.unsubscribe() })
    }

    protected override def onNext(port: Port): Unit = {
        invalidate(port.deviceTag)
    }
}
