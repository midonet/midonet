/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
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
