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
package org.midonet.midolman.state

import java.util.{UUID, HashMap => JHashMap,
                  HashSet => JHashSet, Iterator => JIterator}
import scala.concurrent.{ExecutionContext, Future}

import akka.actor.ActorSystem

import org.midonet.midolman.state.ConnTrackState.ConnTrackKey
import org.midonet.midolman.state.NatState.NatKey
import org.midonet.midolman.state.NatState.NatBinding


class MockStateStorage extends FlowStateStorage {
    override def touchConnTrackKey(k: ConnTrackKey, strongRef: UUID,
            weakRefs: JIterator[UUID]): Unit = {}

    override def touchNatKey(k: NatKey, v: NatBinding, strongRef: UUID,
            weakRefs: JIterator[UUID]): Unit = {}

    override def submit(): Unit = {}

    override def fetchStrongConnTrackRefs(port: UUID)
            (implicit ec: ExecutionContext, as: ActorSystem) =
                Future.successful(new JHashSet[ConnTrackKey]())

    override def fetchWeakConnTrackRefs(port: UUID)
            (implicit ec: ExecutionContext, as: ActorSystem) =
                Future.successful(new JHashSet[ConnTrackKey]())

    override def fetchStrongNatRefs(port: UUID)
            (implicit ec: ExecutionContext, as: ActorSystem) =
                Future.successful(new JHashMap[NatKey, NatBinding]())

    override def fetchWeakNatRefs(port: UUID)
            (implicit ec: ExecutionContext, as: ActorSystem) =
                Future.successful(new JHashMap[NatKey, NatBinding]())
}
