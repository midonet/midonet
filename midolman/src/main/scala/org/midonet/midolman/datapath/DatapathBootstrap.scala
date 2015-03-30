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
import org.midonet.odp.{Datapath, OvsNetlinkFamilies, OvsProtocol}
import org.slf4j.LoggerFactory

object DatapathBootstrap {
    private val log = LoggerFactory.getLogger(getClass)

    class DatapathBootstrapError(msg: String, cause: Throwable = null)
        extends Exception(msg, cause)

    def bootstrap(
            config: MidolmanConfig,
            channelFactory: NetlinkChannelFactory,
            families: OvsNetlinkFamilies): Datapath = {

        val channel = channelFactory.create(blocking = false)
        val writer = new NetlinkBlockingWriter(channel)
        val reader = new NetlinkTimeoutReader(channel, 1 minute)
        val buf = BytesUtil.instance.allocate(2 * 1024)
        val protocol = new OvsProtocol(channel.getLocalAddress.getPid, families)
        try {
            datapath("delete", writer, reader, buf) (
                protocol.prepareDatapathDel(0, config.datapathName, _))
            buf.clear()
            datapath("create", writer, reader, buf) (
                protocol.prepareDatapathCreate(config.datapathName, _))
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

    private def datapath(
            op: String,
            writer: NetlinkBlockingWriter,
            reader: NetlinkTimeoutReader,
            buf: ByteBuffer)
           (prepare: ByteBuffer => Unit): Unit =
        try {
            prepare(buf)
            writer.write(buf)
            buf.clear()
            reader.read(buf)
            buf.flip()
        } catch { case t: Throwable =>
            throw new DatapathBootstrapError(s"Failed to $op the datapath", t)
        }
}
