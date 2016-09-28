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

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

import akka.actor.Scheduler

import org.midonet.midolman.io.UpcallDatapathConnectionManager
import org.midonet.midolman.vpp.VppApi
import org.midonet.netlink.rtnetlink.LinkOps
import org.midonet.util.concurrent.{FutureSequenceWithRollback, FutureTaskWithRollback}

object VppSetup {

    private val VppConnectionName = "midonet"
    private val VppConnectMaxRetries = 10
    private val VppConnectDelayMs = 1000

    private trait MacSource {
        def macAddress: Option[Array[Byte]]
    }

    private trait VppSource {
        def vppApi: Option[VppApi]
    }

    private class VethPairSetup(override val name: String,
                        devName: String,
                        peerName: String)
                       (implicit ec: ExecutionContext)
        extends FutureTaskWithRollback with MacSource {

        var macAddress: Option[Array[Byte]] = None

        @throws[Exception]
        override def execute(): Future[Any] = Future {
            val veth = LinkOps.createVethPair(devName, peerName, up=true)
            macAddress = Some(veth.dev.mac.getAddress)
        }

        @throws[Exception]
        override def rollback(): Future[Any] = Future {
            LinkOps.deleteLink(devName)
        }
    }

    private class VppDevice(override val name: String,
                    deviceName: String,
                    vppSource: VppSource,
                    macSource: MacSource)
                   (implicit ec: ExecutionContext)
        extends FutureTaskWithRollback {

        var interfaceIndex: Option[Int] = None

        @throws[Exception]
        override def execute(): Future[Any] = {
            val vppApi = vppSource.vppApi.get
            vppApi.createDevice(deviceName, macSource.macAddress)
                .flatMap[Int] { result =>
                    vppApi.setDeviceAdminState(result.swIfIndex,
                                               isUp = true)
                        .map( _ => result.swIfIndex)
                } andThen {
                    case Success(index) => interfaceIndex = Some(index)
                }
        }

        @throws[Exception]
        override def rollback(): Future[Any] = {
            val vppApi = vppSource.vppApi.get
            vppApi.deleteDevice(deviceName)
        }
    }

    private class VppConnect(scheduler: Scheduler)
                            (implicit ec: ExecutionContext)
        extends FutureTaskWithRollback with VppSource {

        override def name: String = "vpp connect"

        var vppApi: Option[VppApi] = None

        @throws[Exception]
        override def execute(): Future[Any] = {
            vppApi = None
            createApiConnection(VppConnectMaxRetries)
        }

        private def createApiConnection(retriesLeft: Int): Future[VppApi] = {

            val promise = Promise[VppApi]

            def createApi(): Unit = {
                Try {
                    new VppApi(VppConnectionName)
                } match {
                    case Success(api) =>
                        vppApi = Some(api)
                        promise.trySuccess(api)
                    case Failure(err) if retriesLeft > 0 =>
                        scheduler.scheduleOnce(VppConnectDelayMs millis) {
                            createApi()
                        }
                    case Failure(err) => promise.tryFailure(err)
                }
            }

            createApi()
            promise.future
        }

        @throws[Exception]
        override def rollback(): Future[Any] = {
            vppApi = None
            Future.successful(())
        }
    }
}

class VppSetup(uplinkInterface: String,
               upcallConnManager: UpcallDatapathConnectionManager,
               datapathState: DatapathState,
               scheduler: Scheduler)
              (implicit ec: ExecutionContext)
    extends FutureSequenceWithRollback("VPP setup") {

    import VppSetup._

    private val uplinkVppName = uplinkInterface + "-uv"
    private val uplinkOvsName = uplinkInterface + "-uo"

    private val vppConnect = new VppConnect(scheduler)
    private val uplinkVeth = new VethPairSetup("uplink veth pair",
                                       uplinkVppName,
                                       uplinkOvsName)

    private val uplinkVpp = new VppDevice("uplink device at vpp",
                                  uplinkVppName,
                                  vppConnect,
                                  uplinkVeth)

    /*
     * setup the tasks, in execution order
     */
    add(vppConnect)
    add(uplinkVeth)
    add(uplinkVpp)
}
