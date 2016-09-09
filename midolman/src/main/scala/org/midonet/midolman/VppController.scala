/*
 * Copyright 2016 Midokura SARL
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

import java.util.function.Consumer

import akka.actor.Actor

import com.google.inject.Inject

import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.logging.ActorLogWithoutPath
import org.midonet.midolman.Midolman.MIDOLMAN_ERROR_CODE_VPP_PROCESS_DIED
import org.midonet.util.process.MonitoredDaemonProcess

object VppController extends Referenceable {

    override val Name: String = "VppController"
    val VppProcessMaximumStarts = 3
    val VppProcessMaximumPeriod = 30000

}


class VppController @Inject() (config: MidolmanConfig)
    extends Actor with ActorLogWithoutPath {

    import org.midonet.midolman.VppController._

    override def logSource = "org.midonet.vpp-controller"

    private var vppProcess: MonitoredDaemonProcess = _

    override def preStart(): Unit = {
        super.preStart()
        val exitAction = new Consumer[Exception] {
            override def accept(t: Exception): Unit = {
                log.debug(t.getMessage)
                Midolman.exitAsync(MIDOLMAN_ERROR_CODE_VPP_PROCESS_DIED)
            }
        }
        vppProcess = new MonitoredDaemonProcess(
            "/usr/share/midolman/vpp-start", log.underlying, "org.midonet.vpp",
            VppProcessMaximumStarts, VppProcessMaximumPeriod, exitAction)
    }

    override def receive: Receive = {
        case _ =>
            log warn "Unknown message."
    }
}


