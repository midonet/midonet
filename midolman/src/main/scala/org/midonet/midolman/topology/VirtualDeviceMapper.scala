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

import org.midonet.sdn.flows.FlowTagger.FlowTag

import akka.actor.ActorSystem

import org.midonet.midolman.FlowController
import org.midonet.midolman.FlowController.InvalidateFlowsByTag
import org.midonet.midolman.topology.VirtualTopology.{Device, VirtualDevice}

/**
 * A base class for a virtual device observable [[OnSubscribe]] handler. This class
 * subclasses DeviceMapper and generate flow tag invalidations upon receiving
 * notifications from the underlying observable. Virtual devices extend this class.
 */
abstract class VirtualDeviceMapper[D <: VirtualDevice](id: UUID, vt: VirtualTopology)
                                                      (implicit m: Manifest[D],
                                                       actorSystem: ActorSystem)
    extends DeviceMapper[D](id, vt)(m) {

    override final protected def onDeviceChanged(device: Device) = device match {
        case d: VirtualDevice => FlowController.getRef ! InvalidateFlowsByTag(d.deviceTag)
        case _ =>
    }

    protected final def onDeviceChanged(tag: FlowTag): Unit = {
        FlowController.getRef ! InvalidateFlowsByTag(tag)
    }
}