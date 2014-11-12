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

import scala.util.Try

import com.typesafe.scalalogging.Logger
import org.slf4j.LoggerFactory

import org.midonet.cluster.client.Port
import org.midonet.cluster.data.Bridge
import org.midonet.midolman.DatapathController.DatapathReady
import org.midonet.midolman.host.interfaces.InterfaceDescription
import org.midonet.midolman.topology.{LocalPortActive,
    PortActivationStateMachine, VirtualTopologyActor}

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
class MtuIncreaseActor extends PortActivationStateMachine
        with SubscriberActor {
    import context.system
    import org.midonet.midolman.MtuIncreaseActor._
    import scala.concurrent._
    import scala.sys.process._

    implicit val executorContext = context.dispatcher

    override def subscribedClasses =
        Seq(DatapathReady.getClass, LocalPortActive.getClass)

    override def logSource = "org.midonet.mtu-increase-actor"
    implicit val logger: Logger = log
    // This dpState is assigned when DatapathReady message gets passed.
    var dpState: DatapathState = _

    private def increaseMtu(ifDesc: InterfaceDescription): Future[_] = {
        val itfName: String = ifDesc.getName
        // Check if a associated device with the port is a bridge and if it is,
        // increase MTU with the "ip" command.
        val increaseMtuOption: Option[Future[_]] = for {
            vPortId <- dpState.getVportForInterface(itfName)
            vPort: Port = VirtualTopologyActor.tryAsk[Port](vPortId)
            vDeviceId: UUID = vPort.deviceID
            vBridgeTry = Try(VirtualTopologyActor.tryAsk[Bridge](vDeviceId))
            if vBridgeTry.isSuccess
        } yield Future(Some(
                s"ip link set mtu $BOUND_INTERFACE_MTU dev $itfName".!))
        increaseMtuOption.getOrElse(Future.successful(None))
    }

    override def handlePortActivation(msg: LocalPortActive): Future[_] = {
        val increaseMtuWithIfDescOption: Option[Future[_]] = for {
            portNo <- dpState.getDpPortNumberForVport(msg.portID)
            itfName <- dpState.getDpPortName(portNo)
            ifDesc <- dpState.getDescForInterface(itfName)
        } yield increaseMtu(ifDesc) flatMap {
                case Some(0) =>
                    log.debug(s"Succeeded to increase the MTU of $itfName")
                    Future.successful(0)
                case Some(statusCode: Int) =>
                    log.warn(s"Failed to increase the MTU of $itfName " +
                        s"with status code $statusCode.")
                    Future.successful(statusCode)
                case _ =>
                    Future.successful(None)
            }
        increaseMtuWithIfDescOption.getOrElse(Future.successful(None))
    }

    // Initial state just to receive DatapathReady and assign the passed
    // DatapathState to dpState.
    private def waitForDatapathReady: Receive = {
        case DatapathReady(_, passedDpState) =>
            this.dpState = passedDpState
            context.become(super.receive)
        case _ =>
            // noop unless it gets DatapathReady message.
    }

    // Wait for the datapath state to be initialize and then proceed the
    // regular LocalPortActive message handling.
    override def receive = waitForDatapathReady
}

object MtuIncreaseActor extends Referenceable {
    val log = LoggerFactory.getLogger(classOf[MtuIncreaseActor])

    override val Name = "MtuIncreaseActor"
    val BOUND_INTERFACE_MTU = 65000L
}
