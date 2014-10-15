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

package org.midonet.brain.services.topology.server

import scala.concurrent.{Future, Promise}

import com.google.protobuf.Message
import rx.{Subscription, Observer}

import org.midonet.brain.services.topology.common.{State, ProtocolFactory}
import org.midonet.cluster.util.UUIDUtil

/**
 * Protocol handling the server-side communication
 */
class ServerProtocolFactory(val sMgr: SessionInventory)
    extends ProtocolFactory {
    override def start(output: Observer[Message]):
    (State, Future[Subscription]) = {
        val subs = Promise[Subscription]()
        (Ready((id, success) => try {
            val session = sMgr.claim(UUIDUtil.fromProto(id))
            subs success session.observable.subscribe(output)
            Some(session)
        } catch {
            case e: Exception =>
                subs failure e
                None
        }), subs.future)
    }
}
