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

package org.midonet.midolman.host.services

import java.nio.ByteBuffer
import java.util.Objects

import org.midonet.netlink._
import org.midonet.netlink.rtnetlink.RtnetlinkProtocol
import java.util.concurrent.LinkedBlockingQueue

import org.midonet.midolman.logging.MidolmanLogging
import org.midonet.netlink.exceptions.NetlinkException

object TcRequestOps {
    val ADDFILTER = 1
    val REMQDISC = 2
}

class TcRequest(val op: Int, val ifindex: Int, val rate: Int = 0,
                val burst: Int = 0) {
    def isAdd = op == TcRequestOps.ADDFILTER
}

/*
 * This class will start a thread that blocks waiting for requests to be
 * dropped into 'q'.
 *
 * It will take requests from the q and translate them into equivalent tc
 * netlink messages, which it will then send over to the kernel.
 */
class TcRequestHandler(channelFactory: NetlinkChannelFactory,
                       q: LinkedBlockingQueue[TcRequest])
        extends MidolmanLogging {

    val EEXIST = 17

    val channel = channelFactory.create(blocking = true,
        NetlinkProtocol.NETLINK_ROUTE)

    val reader = new NetlinkReader(channel)
    val writer = new NetlinkBlockingWriter(channel)

    val protocol = new RtnetlinkProtocol(channel.getLocalAddress.getPid)

    class TcConf(val ifindex: Int, val rate: Int, val burst: Int) {
        override def equals(other: Any): Boolean = {
            other match {
                case other: TcConf =>
                    ifindex == other.ifindex &&
                    rate == other.rate &&
                    burst == other.burst
                case _ => false
            }
        }

        override def hashCode: Int = Objects.hash(ifindex.toString, rate.toString, burst.toString)
    }

    def writeRead(buf: ByteBuffer): Unit = {
        writer.write(buf)
        buf.clear()
        reader.read(buf)
        buf.clear()
    }

    def processDelete(ifindex: Int): Unit = {
        val buf = BytesUtil.instance.allocateDirect(5000)
        protocol.prepareDeleteIngressQdisc(buf, ifindex)
        writeRead(buf)
    }

    def processAdd(tickInUsec: Double, ifindex: Int, rate: Int,
                   burst: Int, mtu: Int = 65335): Unit = {
        val buf = BytesUtil.instance.allocateDirect(5000)

        protocol.prepareAddIngressQdisc(buf, ifindex)

        try {
            writeRead(buf)
        } catch {
            case e: NetlinkException if e.errorCode == EEXIST =>
                protocol.prepareDeleteIngressQdisc(buf, ifindex)
                writeRead(buf)
        }

        protocol.prepareAddPoliceFilter(buf, ifindex, rate, burst, mtu, tickInUsec)
        writeRead(buf)
    }

    def getTicksInUsec: Double = {
        val psched = scala.io.Source.fromFile("/proc/net/psched")
            .getLines.toList.head.split(" ")
            .map(Integer.parseInt(_, 16))

        val t2us = psched(0).toDouble
        val us2t = psched(1).toDouble

        t2us / us2t
    }

    val processingThread = new Thread() {
        val tickInUsec = getTicksInUsec

        override def run(): Unit = {
            while (true) {
                val request = q.take()

                if (request.isAdd) {
                    processAdd(tickInUsec, request.ifindex, request.rate,
                               request.burst)
                } else {
                    processDelete(request.ifindex)
                }
            }
        }
    }

    def start() = processingThread.start()
}
