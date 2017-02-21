/*
 * Copyright 2015 Midokura SARL
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

package org.midonet

import java.nio.ByteBuffer

import org.junit.runner.RunWith
import org.scalatest._
import org.scalatest.junit.JUnitRunner

import rx.Observer

import org.midonet.netlink._
import org.midonet.netlink.exceptions.NetlinkException
import org.midonet.netlink.rtnetlink.LinkOps
import org.midonet.odp._
import org.midonet.odp.ports.NetDevPort

@RunWith(classOf[JUnitRunner])
class ReclaimTest extends FeatureSpec
                 with BeforeAndAfterAll
                 with Matchers {

    feature("Datapath is reclaimed") {
        scenario ("Reclaim or claim") {
            val dpName = "reclaim-test"
            val vethName = "reclaimdp"

            val buf = BytesUtil.instance.allocateDirect(512)
            val channelFactory = new NetlinkChannelFactory()

            val channel: NetlinkChannel = channelFactory.create(
                blocking = true)

            val writer = new NetlinkWriter(channel)
            val reader = new NetlinkReader(channel)
            val families = OvsNetlinkFamilies.discover(channel)
            val protocol = new OvsProtocol(channel.getLocalAddress.getPid,
                                           families)
            val datapath = try {
                println("Getting datapath")
                protocol.prepareDatapathGet(0, dpName, buf)
                NetlinkUtil.rpc(buf, writer,
                                reader, Datapath.buildFrom)
            } catch {
                case e: NetlinkException =>
                    println(s"Not found, creating datapath")
                    protocol.prepareDatapathCreate(dpName, buf)
                    NetlinkUtil.rpc(buf, writer,
                                    reader, Datapath.buildFrom)
            }

            println(s"Datapath: $datapath")

            try {
                println("Loading data port")
                protocol.prepareDpPortGet(datapath.getIndex, null,
                                          vethName, buf)
                val port = NetlinkUtil.rpc(buf, writer, reader, DpPort.buildFrom)
                println(s"Port found ($port), setting")
                protocol.prepareDpPortSet(datapath.getIndex,
                                          port, buf)
                val newPort = NetlinkUtil.rpc(buf, writer, reader, DpPort.buildFrom)
                println(s"Port set ($newPort)")
            } catch {
                case e: NetlinkException =>
                    println("Creating data port")
                    LinkOps.createVethPair(vethName, vethName+"-ns")
                    protocol.prepareDpPortCreate(
                        datapath.getIndex,
                        new NetDevPort(vethName), buf)
                    val port = NetlinkUtil.rpc(buf, writer, reader, DpPort.buildFrom)
                    println(s"Port created ($port)")
            }

            NetlinkUtil.readNetlinkNotifications(
                channel,
                reader, NetlinkMessage.HEADER_SIZE,
                new Observer[ByteBuffer]() {
                    override def onNext(bb: ByteBuffer): Unit = {
                        println(s"Got buffer ${bb.limit}")
                    }
                    override def onError(ex: Throwable): Unit = {
                    }
                    override def onCompleted(): Unit = {}
                })

        }

    }
}
