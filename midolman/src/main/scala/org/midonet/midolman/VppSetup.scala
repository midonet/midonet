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
import scala.util.Success

import org.midonet.midolman.logging.MidolmanLogging
import org.midonet.midolman.simulation.RouterPort
import org.midonet.midolman.vpp.VppApi
import org.midonet.netlink.rtnetlink.LinkOps
import org.midonet.odp.DpPort
import org.midonet.util.concurrent.{FutureSequenceWithRollback, FutureTaskWithRollback}

object VppSetup extends MidolmanLogging {

    override def logSource = s"org.midonet.vpp-setup"

    private trait MacAddressProvider {
        def macAddress: Option[Array[Byte]]
    }

    private trait VppInterfaceProvider {
        def vppInterface: Option[Int]
    }

    private class VethPairSetup(override val name: String,
                        devName: String,
                        peerName: String)
                       (implicit ec: ExecutionContext)
        extends FutureTaskWithRollback with MacAddressProvider {

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
                    vppApi: VppApi,
                    macSource: MacAddressProvider)
                   (implicit ec: ExecutionContext)
        extends FutureTaskWithRollback with VppInterfaceProvider {

        var vppInterface: Option[Int] = None

        @throws[Exception]
        override def execute(): Future[Any] = {
            vppApi.createDevice(deviceName, macSource.macAddress)
                .flatMap[Int] { result =>
                    vppApi.setDeviceAdminState(result.swIfIndex,
                                               isUp = true)
                        .map( _ => result.swIfIndex)
                } andThen {
                    case Success(index) => vppInterface = Some(index)
                }
        }

        @throws[Exception]
        override def rollback(): Future[Any] = {
            vppApi.deleteDevice(deviceName)
        }
    }

    private class VppIpAddr(override val name: String,
                            vppApi: VppApi,
                            deviceId: VppInterfaceProvider,
                            address: Array[Byte],
                            prefix: Byte)
                           (implicit ec: ExecutionContext)
        extends FutureTaskWithRollback {
        require(address.length == 4 || address.length == 16)

        @throws[Exception]
        override def execute(): Future[Any] = addDelIpAddress(isAdd = true)

        @throws[Exception]
        override def rollback(): Future[Any] = addDelIpAddress(isAdd = false)

        private def addDelIpAddress(isAdd: Boolean) = {
            vppApi.addDelDeviceAddress(deviceId.vppInterface.get,
                                       address,
                                       prefix,
                                       isIpv6 = address.length != 4,
                                       isAdd)
        }
    }

    private class OvsBind(override val name: String,
                          vppOvs: VppOvs,
                          endpointName: String)
            extends FutureTaskWithRollback {
        var dpPort: Option[DpPort] = None

        @throws[Exception]
        override def execute(): Future[Any] = {
            try {
                dpPort = Some(vppOvs.createDpPort(endpointName))
                Future.successful(Unit)
            } catch {
                case e: Throwable => Future.failed(e)
            }
        }

        @throws[Exception]
        override def rollback(): Future[Any] = {
            dpPort match {
                case Some(port) => try {
                    vppOvs.deleteDpPort(port)
                    Future.successful(Unit)
                } catch {
                    case e: Throwable => Future.failed(e)
                }
                case _ =>
                    Future.successful(Unit)
            }
        }

        @throws[IllegalStateException]
        def getPortNo: Int = dpPort match {
            case Some(port) => port.getPortNo
            case _ => throw new IllegalStateException(
                s"Datapath Port hasn't been created for $endpointName")
        }
    }

    private class FlowInstall(override val name: String,
                              vppOvs: VppOvs,
                              ep1fn: () => Int,
                              ep2fn: () => Int)
            extends FutureTaskWithRollback {
        @throws[Exception]
        override def execute(): Future[Any] = {
            try {
                vppOvs.addIpv6Flow(ep1fn(), ep2fn())
                vppOvs.addIpv6Flow(ep2fn(), ep1fn())
                Future.successful(Unit)
            } catch {
                case e: Throwable => Future.failed(e)
            }
        }

        @throws[Exception]
        override def rollback(): Future[Any] = {
            var firstException: Option[Throwable] = None
            try {
                vppOvs.clearIpv6Flow(ep1fn(), ep2fn())
            } catch {
                case e: Throwable => firstException = Some(e)
            }
            try {
                vppOvs.clearIpv6Flow(ep2fn(), ep1fn())
            } catch {
                case e: Throwable => if (firstException.isEmpty) {
                    firstException = Some(e)
                }
            }
            firstException match {
                case None => Future.successful(Unit)
                case Some(e) => Future.failed(e)
            }
        }
    }
}

class VppSetup(uplinkPort: RouterPort,
               uplinkPortDpNo: Int,
               vppApi: VppApi,
               vppOvs: VppOvs)
              (implicit ec: ExecutionContext)
    extends FutureSequenceWithRollback("VPP setup") {

    import VppSetup._

    private val uplinkSuffix = uplinkPort.id.toString.substring(0, 7)
    private val uplinkVppName = s"vpp-$uplinkSuffix"
    private val uplinkOvsName = s"ovs-$uplinkSuffix"
    private val uplinkVppAddr = Array[Byte](0x20, 0x01, 0, 0, 0, 0, 0, 0,
                                            0, 0, 0, 0, 0, 0, 0, 0x1)
    private val uplinkVppPrefix: Byte = 64

    private val uplinkVeth = new VethPairSetup("uplink veth pair",
                                       uplinkVppName,
                                       uplinkOvsName)

    private val uplinkVpp = new VppDevice("uplink device at vpp",
                                  uplinkVppName,
                                  vppApi,
                                  uplinkVeth)

    private val ipAddrVpp = new VppIpAddr("uplink IPv6 address at vpp",
                                          vppApi,
                                          uplinkVpp,
                                          uplinkVppAddr,
                                          uplinkVppPrefix)
    private val ovsBind = new OvsBind("ovs bind for vpp uplink veth",
                                      vppOvs,
                                      uplinkOvsName)
    private val vppFlows = new FlowInstall("install ipv6 flows",
                                           vppOvs,
                                           () => { ovsBind.getPortNo },
                                           () => { uplinkPortDpNo })

    /*
     * setup the tasks, in execution order
     */
    add(uplinkVeth)
    add(uplinkVpp)
    add(ipAddrVpp)
    add(ovsBind)
    add(vppFlows)
}
