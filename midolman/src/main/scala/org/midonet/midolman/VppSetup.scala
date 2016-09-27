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

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

import org.midonet.midolman.io.UpcallDatapathConnectionManager
import org.midonet.midolman.vpp.VppApi
import org.midonet.netlink.rtnetlink.LinkOps
import org.midonet.util.concurrent.{FutureSequenceWithRollback, FutureTaskWithRollback}

object VppSetup {

    trait MacSource {
        def getMac: Option[Array[Byte]]
    }

    trait VppSource {
        def getVpp: VppApi
    }

    object VppConnect extends FutureTaskWithRollback with VppSource {

        override def name: String = "vpp connect"

        var vppApi: VppApi = _

        override def getVpp: VppApi = vppApi

        @throws[Exception]
        override def execute(): Future[Any] = Future {
            var count = 0
            vppApi = null
            do {
                Try{ new VppApi("midolman") } match {
                    case Success(api) =>
                        vppApi = api
                    case Failure(err) =>
                        count += 1
                        if (count < 10) {
                            Thread.sleep(1000)
                        } else {
                            throw err
                        }
                }
            } while(vppApi eq null)
        }

        @throws[Exception]
        override def rollback(): Future[Any] = Future {
            vppApi = null
        }
    }

    class VethPairSetup(override val name: String,
                        devName: String,
                        peerName: String)
                       (implicit ec: ExecutionContext)
        extends FutureTaskWithRollback with MacSource {

        var macAddress: Option[Array[Byte]] = None

        override def getMac: Option[Array[Byte]] = macAddress

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

    class VppDevice(override val name: String,
                    deviceName: String,
                    vppSource: VppSource,
                    macSource: MacSource)
                   (implicit ec: ExecutionContext)
        extends FutureTaskWithRollback {

        var interfaceIndex: Option[Int] = None

        @throws[Exception]
        override def execute(): Future[Any] = {
            val vppApi = vppSource.getVpp
            vppApi.createDevice(deviceName, macSource.getMac)
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
            val vppApi = vppSource.getVpp
            vppApi.deleteDevice(deviceName)
        }
    }
}

class VppSetup(uplinkInterface: String,
               upcallConnManager: UpcallDatapathConnectionManager,
               datapathState: DatapathState)
              (implicit ec: ExecutionContext)
    extends FutureSequenceWithRollback("VPP setup") {

    import VppSetup._

    val uplinkVppName = "uplink-vpp-" + uplinkInterface
    val uplinkOvsName = "uplink-ovs-" + uplinkInterface

    val uplinkVeth = new VethPairSetup("uplink veth pair",
                                       uplinkVppName,
                                       uplinkOvsName)

    val uplinkVpp = new VppDevice("uplink device at vpp",
                                  uplinkVppName,
                                  VppConnect,
                                  uplinkVeth)


    /*
     * setup the tasks, in execution order
     */

    add(VppConnect)
    add(uplinkVeth)
    add(uplinkVpp)
}
