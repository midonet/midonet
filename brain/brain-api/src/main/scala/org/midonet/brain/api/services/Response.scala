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

package org.midonet.brain.api.services

import java.util.UUID

import org.midonet.cluster.models.{Topology, Commons}
import org.midonet.cluster.rpc.Commands
import org.midonet.cluster.util.UUIDUtil

abstract class Response
case class Ack(reqId: UUID) extends Response
case class NAck(reqId: UUID) extends Response
case class Deletion(Id: Commons.UUID) extends Response
case class UpdateNetwork(devProto: Topology.Network) extends Response
case class UpdatePort(devProto: Topology.Port) extends Response
case class UpdateHost(devProto: Topology.Host) extends Response
case class UpdateTunnelZone(devProto: Topology.TunnelZone) extends Response

object Response {
    def encode(rsp: Response): Commands.Response = rsp match {
        case Ack(reqId) =>
            Commands.Response.newBuilder().setAck(
                Commands.Response.Ack.newBuilder()
                    .setReqId(UUIDUtil.toProto(reqId))
                    .build).build
        case NAck(reqId) =>
            Commands.Response.newBuilder().setNack(
                Commands.Response.NAck.newBuilder()
                    .setReqId(UUIDUtil.toProto(reqId))
                    .build).build
        case Deletion(id) =>
            Commands.Response.newBuilder().setDeletion(
                Commands.Response.Deletion.newBuilder()
                    .setId(id)
                    .build).build
        case UpdateNetwork(dev) =>
            Commands.Response.newBuilder().setUpdate(
                Commands.Response.Update.newBuilder()
                    .setNetwork(dev)
                    .build).build
        case UpdatePort(dev) =>
            Commands.Response.newBuilder().setUpdate(
                Commands.Response.Update.newBuilder()
                    .setPort(dev)
                    .build).build
        case UpdateHost(dev) =>
            Commands.Response.newBuilder().setUpdate(
                Commands.Response.Update.newBuilder()
                    .setHost(dev)
                    .build).build
        case UpdateTunnelZone(dev) =>
            Commands.Response.newBuilder().setUpdate(
                Commands.Response.Update.newBuilder()
                    .setTunnelZone(dev)
                    .build).build
    }
}

