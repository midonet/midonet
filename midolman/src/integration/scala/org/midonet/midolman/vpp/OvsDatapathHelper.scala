/*
 * Copyright 2016 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.midonet.midolman.vpp

import java.nio.ByteBuffer

import scala.concurrent.duration._

import com.typesafe.scalalogging.Logger

import org.midonet.ErrorCode
import org.midonet.netlink._
import org.midonet.netlink.exceptions.NetlinkException
import org.midonet.odp.{Datapath, OvsNetlinkFamilies, OvsProtocol}

/**
  * Create/delete Ovs Datapath, mainly for test purposes
  */
private[vpp] trait OvsDatapathHelper {

    def createDatapath(name: String): Datapath =  {
        val buf = BytesUtil.instance.allocate(2 * 1024)
        try {
            doDatapathOp((protocol) => {
                protocol.prepareDatapathDel(0, name, buf)
                buf
            })
        } catch {
            case t: NetlinkException
                if (t.getErrorCodeEnum == ErrorCode.ENODEV ||
                    t.getErrorCodeEnum == ErrorCode.ENOENT ||
                    t.getErrorCodeEnum == ErrorCode.ENXIO) =>
        }
        buf.clear()
        doDatapathOp((protocol) => {
            protocol.prepareDatapathCreate(name, buf)
            buf
        })
        buf.position(NetlinkMessage.GENL_HEADER_SIZE)
        Datapath.buildFrom(buf)
    }

    def deleteDatapath(datapath: Datapath, log: Logger): Unit = {
        val buf = BytesUtil.instance.allocate(2 * 1024)
        try {
            doDatapathOp((protocol) => {
                protocol.prepareDatapathDel(
                    0, datapath.getName, buf)
                buf
            })
        } catch {
            case t: Throwable => log.warn("Error deleting datapath $name", t)
        }
    }

    private def doDatapathOp(opBuf: (OvsProtocol) => ByteBuffer): Unit = {
        val factory = new NetlinkChannelFactory()
        val famchannel = factory.create(blocking = true,
                                        NetlinkProtocol.NETLINK_GENERIC,
                                        NetlinkUtil.NO_NOTIFICATION)
        val families = OvsNetlinkFamilies.discover(famchannel)
        famchannel.close()

        val channel = factory.create(blocking = false)
        val writer = new NetlinkBlockingWriter(channel)
        val reader = new NetlinkTimeoutReader(channel, 1 minute)
        val protocol = new OvsProtocol(channel.getLocalAddress.getPid, families)

        try {
            val buf = opBuf(protocol)
            writer.write(buf)
            buf.clear()
            reader.read(buf)
            buf.flip()
        } finally {
            channel.close()
        }
    }

}
