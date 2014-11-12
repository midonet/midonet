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

import com.typesafe.scalalogging.Logger
import org.midonet.cluster.client.Port
import org.midonet.cluster.data.Bridge
import org.midonet.midolman.datapath.DatapathPortEntangler
import org.midonet.midolman.host.interfaces.InterfaceDescription
import org.midonet.midolman.topology.{LocalPortActive, VirtualTopologyActor,
    PortActivationStateMachine}
import org.slf4j.LoggerFactory

import scala.util.{Failure, Success, Try}

/**
 * MtuIncreaseActor subscribes LocalPortActive events and and trigger
 * handlePortActivation, which delegates the actual process to increaseMtu and
 * increase the MTU of the interface associated with the activated port to 65K
 * bytes.
 *
 * MtuIncreaseActor should be pinned to a dedicated thread and run concurrently.
 * It increase the MTU size if the interface is TUN/TAP and the associated
 * virtual port with the interface is a virtual bridge port, namely if the port
 * is a materialized bridge port, with "ip" command executed in a Future. If the
 * port is not a materialized bridge port, it returns a null-filled Future
 * instance and does nothing. Or it returns the Future contains a returned
 * status code of the ip command, otherwise the exception thrown by the command.
 */
trait MtuIncreaseActor extends PortActivationStateMachine {
    import scala.concurrent._
    import scala.sys.process._
    import context.system
    import MtuIncreaseActor._

    implicit protected def executor: ExecutionContextExecutor =
        context.dispatcher

    override def logSource = "org.midonet.mtu-increase-actor"
    implicit val logger: Logger = log
    // FIXME(tfukushima): To get the UUID of the virtual port associated with
    // the interface, I need to hold this class which has a map from the
    // interface name to the virtual port UUID.
    val portEntangler: DatapathPortEntangler

    private def increaseMtu(ifDesc: InterfaceDescription): Future[_] = {
        val ifname: String = ifDesc.getName
        val increaseMtuOption: Option[Future[_]] = for {
            // Check if a associated device with the port is a bridge.
            vPortId <- portEntangler.interfaceToVport.get(ifname)
            vPort: Port = VirtualTopologyActor.tryAsk[Port](vPortId)
            vDeviceId: UUID = vPort.deviceID
            vBridgeTry = Try(VirtualTopologyActor.tryAsk[Bridge](vDeviceId))
            if vBridgeTry.isSuccess
        } yield Future(s"ip link set mtu $BOUND_INTERFACE_MTU dev $ifname".!)
        increaseMtuOption.getOrElse(Future.successful(null))
    }

    override def handlePortActivation(msg: LocalPortActive): Future[_] = {
        val increaseMtuWithIfDescOption: Option[Future[_]] = for {
            ifDesc <- msg.desc
            ifname = ifDesc.getName
        } yield increaseMtu(ifDesc) flatMap {
                case Success(0) =>
                    log.debug(s"Succeeded to increase the MTU of $ifname")
                    Future.successful(0)
                case Success(statusCode: Int) =>
                    log.info(s"Failed to increase the MTU of $ifname " +
                        s"with status code $statusCode.")
                    Future.successful(statusCode)
                case Failure(t) =>
                    log.warn(s"Failed to increase the MTU of $ifname:" +
                        t.getMessage)
                    Future.failed(t)
                case _ =>
                    Future.successful(null)
            }
        increaseMtuWithIfDescOption.getOrElse(Future.successful(null))
    }
}

object MtuIncreaseActor extends Referenceable {
    val log = LoggerFactory.getLogger(classOf[MtuIncreaseActor])

    override val Name = "MtuIncreaseActor"
    val BOUND_INTERFACE_MTU = 65000L
}
