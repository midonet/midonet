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

import java.io.FileNotFoundException
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue

import org.midonet.midolman.logging.MidolmanLogging
import org.midonet.netlink._
import org.midonet.netlink.exceptions.NetlinkException
import org.midonet.netlink.rtnetlink.RtnetlinkProtocol

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

    def writeRead(buf: ByteBuffer): Unit = {
        writer.write(buf)
        buf.clear()

        /* We read and don't do anything with the result unless it's an error.
         * If it is an error, this will throw an exception. */
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
                // Delete the current ingress qdisc to reset the
                // configuration.
                protocol.prepareDeleteIngressQdisc(buf, ifindex)
                writeRead(buf)
                protocol.prepareAddIngressQdisc(buf, ifindex)
                /* 
                 * TODO: An ACK is requested in the netlink msg
                 * sent here, however none is returned. We need
                 * to understand why, but functionally there is
                 * no problem.
                 */
                writer.write(buf)
                buf.clear()
        }

        protocol.prepareAddPoliceFilter(buf, ifindex, rate, burst, mtu, tickInUsec)
        writeRead(buf)
    }

    val processingThread = new Thread() {

        val pschedFile = "/proc/net/psched"
        val defaultTicksPerUsec = 15.65
        val ticksPerUsec =  {
            try {
                val psched = scala.io.Source.fromFile(pschedFile)
                    .getLines.toList.head.split(" ")
                    .map(Integer.parseInt(_, 16))

                val us2ns = psched(0).toDouble
                val t2ns = psched(1).toDouble

                us2ns / t2ns
            } catch {
                case fnfe: FileNotFoundException =>
                    log.error(s"$pschedFile not found on system. Using " +
                              s"$defaultTicksPerUsec to measure ticks in a" +
                              s" microsecond")
                    defaultTicksPerUsec
            }
        }
        override def run(): Unit = {
            while (true) {
                val request = q.take()

                request.op match {
                    case TcRequestOps.ADDFILTER =>
                        processAdd(ticksPerUsec,
                                   request.ifindex, request.rate,
                                   request.burst)
                    case TcRequestOps.REMQDISC =>
                        processDelete(request.ifindex)
                }
            }
        }
    }

    def start() = processingThread.start()
}
