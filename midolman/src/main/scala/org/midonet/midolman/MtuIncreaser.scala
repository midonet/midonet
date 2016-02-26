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

import akka.actor.ActorSystem
import com.typesafe.scalalogging.Logger

import org.midonet.midolman.simulation.{BridgePort, Port}
import org.midonet.midolman.topology.VirtualTopology

object MtuIncreaser  {
    // The maximum value of the MTU. See RFC 791.
    // https://tools.ietf.org/html/rfc791
    val BOUND_INTERFACE_MTU = 65535
}

/**
 * MtuIncreaser subscribes to LocalPortActive events to increase the MTU of
 * the interface associated with the activated port to 65k bytes.
 */
trait MtuIncreaser {
    import MtuIncreaser._

    protected val log: Logger

    def increaseMtu(portId: UUID)
                   (implicit actorSystem: ActorSystem): Unit =
        try {
            val port = VirtualTopology.tryGet[Port](portId)
            if (port.isInstanceOf[BridgePort]) {
                val itfName = port.interfaceName
                s"ip link set mtu $BOUND_INTERFACE_MTU dev $itfName".! match {
                    case 0 =>
                        log.debug(s"Successfully increased the MTU of $itfName")
                    case statusCode =>
                        log.warn(s"Failed to increase the MTU of $itfName " +
                                 s"with status code $statusCode.")
                }
            }
        } catch {
            case NotYetException(f, _) =>
                Try(Await.result(f, Duration.Inf)) match {
                    case Failure(t) =>
                        log.error("Failed to increase the MTU of the " +
                                  s"interface associated with port $portId")
                    case Success(_) =>
                        increaseMtu(portId)

                }
            case t: Throwable =>
                log.error(s"Failed to increate MTU of interface for port $portId")
        }
}
