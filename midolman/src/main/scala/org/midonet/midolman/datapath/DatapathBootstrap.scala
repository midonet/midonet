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

package org.midonet.midolman.datapath

import java.nio.ByteBuffer

import scala.concurrent.duration._

import org.midonet.midolman.config.MidolmanConfig
import org.midonet.netlink._
import org.midonet.netlink.exceptions.NetlinkException
import org.midonet.netlink.exceptions.NetlinkException.ErrorCode
import org.midonet.odp.{Datapath, OvsNetlinkFamilies, OvsProtocol}

object DatapathBootstrap {

    class DatapathBootstrapError(msg: String, cause: Throwable = null)
        extends Exception(msg, cause)

    def bootstrap(
            config: MidolmanConfig,
            channelFactory: NetlinkChannelFactory,
            families: OvsNetlinkFamilies): Datapath = {
        val channel = channelFactory.create(blocking = false)
        val pid = channel.getLocalAddress.getPid
        val writer = new NetlinkBlockingWriter(channel)
        val reader = new NetlinkTimeoutReader(channel, 1 minute)
        val buf = BytesUtil.instance.allocate(2 * 1024)
        val protocol = new OvsProtocol(families)
        try {
            protocol.prepareDatapathDel(pid, 0, config.datapathName, buf)
            try {
                writeAndRead(writer, reader, buf)
            } catch {
                case t: NetlinkException if isMissing(t) =>
                case t: Throwable =>
                    throw new DatapathBootstrapError(s"Failed to delete the datapath", t)
            }
            buf.clear()
            protocol.prepareDatapathCreate(pid, config.datapathName, buf)
            try {
                writeAndRead(writer, reader, buf)
            } catch { case t: Throwable =>
                throw new DatapathBootstrapError(s"Failed to create the datapath", t)
            }
            parse(buf)
        } finally {
            channel.close()
        }
    }

    private def parse(buf: ByteBuffer): Datapath =
        try {
            Datapath.buildFrom(buf)
        } catch { case t: Throwable =>
            throw new DatapathBootstrapError("Failed to parse the datapath", t)
        }

    private def isMissing(ex: NetlinkException) = ex.getErrorCodeEnum match {
        case ErrorCode.ENODEV | ErrorCode.ENOENT | ErrorCode.ENXIO => true
        case _ => false
    }

    private def writeAndRead(
            writer: NetlinkBlockingWriter,
            reader: NetlinkTimeoutReader,
            buf: ByteBuffer): Unit = {
        writer.write(buf)
        buf.clear()
        reader.read(buf)
        buf.flip()
    }
}
