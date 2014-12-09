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

package org.midonet.midolman

import java.util.UUID

import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.sys.process._
import scala.util.{Failure, Success, Try}

import akka.actor.Actor
import org.midonet.midolman.host.interfaces.InterfaceDescription.Endpoint

import org.slf4j.LoggerFactory
import com.typesafe.scalalogging.Logger

import org.midonet.midolman.DatapathController.DatapathReady
import org.midonet.midolman.logging.ActorLogWithoutPath
import org.midonet.midolman.topology.devices.{BridgePort, Port}
import org.midonet.midolman.topology.{LocalPortActive, VirtualTopologyActor}

/**
 * MtuIncreaser subscribes to LocalPortActive events to increase the MTU of
 * the interface associated with the activated port to 65K bytes.
 */
class MtuIncreaser extends Actor
                   with ActorLogWithoutPath
                   with SubscriberActor {
    import context.system
    import MtuIncreaser._

    implicit val executorContext = context.dispatcher

    override def logSource = "org.midonet.datapath-control"
    override def subscribedClasses =
        Seq(classOf[DatapathReady], classOf[LocalPortActive])
    implicit val logger: Logger = log

    var dpState: DatapathState = _

    private def receiveLocalPortActive: Receive = {
        case LocalPortActive(id, true) =>
            increaseMtu(id)
    }

    override def receive = {
        case DatapathReady(_, passedDpState) =>
            dpState = passedDpState
            context.become(receiveLocalPortActive)
    }

    private def increaseMtu(portId: UUID): Unit = {
        val port: Port = try {
            VirtualTopologyActor.tryAsk[Port](portId)
        } catch {
            case NotYetException(f, _) =>
                Try(Await.result(f, Duration.Inf)) match {
                    case Failure(t) =>
                        log.error("Failed to increase the MTU of the " +
                                  s"interface associated with port $portId")
                    case Success(_) =>
                        increaseMtu(portId)
                }
                return
        }
        val itfName = port.interfaceName
        val itfDesc = dpState.getDescForInterface(itfName)
        if (port.isInstanceOf[BridgePort] && itfDesc.isDefined &&
            itfDesc.get.getEndpoint == Endpoint.TUNTAP) {
            s"ip link set mtu $BOUND_INTERFACE_MTU dev $itfName".! match {
                case 0 =>
                    log.debug(s"Successfully increased the MTU of $itfName")
                case statusCode: Int =>
                    log.warn(s"Failed to increase the MTU of $itfName " +
                             s"with status code $statusCode.")
            }
        }
    }
}

object MtuIncreaser extends Referenceable {
    val log = LoggerFactory.getLogger(classOf[MtuIncreaser])

    override val Name = "MtuIncreaseActor"
    val BOUND_INTERFACE_MTU = 65000L
}
