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

package org.midonet.netlink.rtnetlink

import java.nio.ByteBuffer

import org.midonet.netlink.NetlinkSerializable


/*
 * This file has helper objects to build the "tcmsg" portion of a netlink
 * packet. Not all netlink packets have tcmsgs attached to them, but the
 * ones used in configuring linux TC do.
 */


/*
 * These constant values were taken from the code of the iproute2 package,
 * where the linux "tc" command is implemented.
 */
object TcmsgType {
    val TCA_UNSPEC: Byte = 0
    val TCA_KIND: Byte = 1
    val TCA_OPTIONS: Byte = 2
    val TCA_STATS: Byte = 3
    val TCA_XSTATS: Byte = 4
    val TCA_RATE: Byte = 5
    val TCA_FCNT: Byte = 6
    val TCA_STATS2: Byte = 7
    val TCA_STAB: Byte = 8
}

object TcmsgBasicType {
    val TCA_BASIC_UNSPEC: Byte = 0
    val TCA_BASIC_CLASSID: Byte = 1
    val TCA_BASIC_EMATCHES: Byte = 2
    val TCA_BASIC_ACT: Byte = 3
    val TCA_BASIC_POLICE: Byte = 4
}

object TcmsgPoliceType {
    val TCA_POLICE_UNSPEC: Byte = 0
    val TCA_POLICE_TBF: Byte = 1
    val TCA_POLICE_RATE: Byte = 2
    val TCA_POLICE_PEAKRATE: Byte = 3
    val TCA_POLICE_AVRATE: Byte = 4
    val TCA_POLICE_RESULT: Byte = 5
}

/*
 * some general helper functions to build a tcmsg. See:
 *
 * http://lxr.free-electrons.com/source/include/uapi/linux/rtnetlink.h#L525
 */
object Tcmsg {

    val TC_H_INGRESS = 0xFFFFFFF1
    val INGRESS_HANDLE = 0xFFFF0000
    val TC_POLICE_SHOT = 2

    // time units is microseconds
    val TIME_UNITS_PER_SEC = 1000000L

    // '3' as a short, in network byte order
    val ETH_P_ALL = 768

    def makeInfo(prio: Int) = prio << 16 | ETH_P_ALL

    def kilobitsToBytes(rate: Int) = (rate * 1000 / 8).toDouble

    def mtuToCellLog(mtu: Int): Int = {
        var cellLog = 1
        while (mtu >> cellLog > 255) {
            cellLog = cellLog + 1
        }
        cellLog
    }

    def usecPerBurst(buffer: Int, rate: Double): Double = {
        TIME_UNITS_PER_SEC * buffer.toDouble/rate
    }

    def addIngressFilter(buf: ByteBuffer, ifindex: Int, prio: Int = 5) =
        addTcmsgToBuf(buf,
                      ifindex,
                      Addr.Family.AF_UNSPEC,
                      0,
                      INGRESS_HANDLE,
                      makeInfo(prio))

    def addIngressQdiscTcmsg(buf: ByteBuffer, ifindex: Int) =
        addTcmsgToBuf(buf, ifindex, Addr.Family.AF_UNSPEC, INGRESS_HANDLE,
                      TC_H_INGRESS, 0)

    def addGetQdiscTcmsg(buf: ByteBuffer, ifindex: Int) =
        addTcmsgToBuf(buf, ifindex, Addr.Family.AF_UNSPEC, 0, 0, 0)

    def addTcmsgToBuf(buf: ByteBuffer,
                      ifindex: Int,
                      family: Int,
                      handle: Int,
                      parent: Int,
                      info: Int) {
        buf.put(family.toByte)
        buf.put(0.toByte) // padding
        buf.putShort(0.toShort) // padding
        buf.putInt(ifindex)
        buf.putInt(handle)
        buf.putInt(parent)
        buf.putInt(info)
    }
}

class TcPolice(rate: Int, burst: Int, mtu: Int, ticksPerUsec: Double)
    extends NetlinkSerializable {
    import Tcmsg._

    def serializeInto(buf: ByteBuffer): Int = {

        val prate = kilobitsToBytes(rate)
        val buffer = burst * 1024
        val pburst = usecPerBurst(buffer, prate)
        val start = buf.position()
        buf.putInt(0) // index
        buf.putInt(Tcmsg.TC_POLICE_SHOT) // action
        buf.putInt(0) // limit
        buf.putInt((pburst*ticksPerUsec/8).toInt)
        buf.putInt(mtu)

        // rate: tc_ratespec
        buf.put(mtuToCellLog(mtu).toByte) // cell log
        buf.put(1.toByte) // linklayer
        buf.putShort(0) // overhead
        buf.putShort(-1) // cell_align
        buf.putShort(0) // mpu
        buf.putInt(prate.toInt) // rate (convert from KB to b)

        // peakrate: tc_ratespec (not used)
        buf.put(0.toByte)
        buf.put(0.toByte)
        buf.putShort(0)
        buf.putShort(0)
        buf.putShort(0)
        buf.putInt(0)

        buf.putInt(0) // refcnt
        buf.putInt(0) // bindcnt
        buf.putInt(0) // capab

        buf.position() - start
    }
}

class TcRtab(mtu: Int, rate: Int, ticksPerUsec: Double) extends NetlinkSerializable {
    import Tcmsg._

    def serializeInto(buf: ByteBuffer): Int = {
        val start = buf.position()

        val bytesPerSec = kilobitsToBytes(rate).toDouble
        val cellLog = mtuToCellLog(mtu)

        for (i <- 1 to 256) {
            val sz = (i << cellLog).toDouble
            val time = TIME_UNITS_PER_SEC * sz / bytesPerSec
            buf.putInt((time * ticksPerUsec).toInt)
        }
        buf.position() - start
    }

}
