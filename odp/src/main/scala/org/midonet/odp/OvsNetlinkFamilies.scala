/*
 * Copyright 2014 Midokura SARL
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

package org.midonet.odp

import java.io.IOException
import java.nio.ByteBuffer

import rx.Observer

import org.midonet.netlink._
import org.midonet.netlink.exceptions.NetlinkException
import org.midonet.odp.family.{PacketFamily, FlowFamily, PortFamily, DatapathFamily}
import org.midonet.util.concurrent.NanoClock

object OvsNetlinkFamilies {

    @throws(classOf[IOException])
    @throws(classOf[NetlinkException])
    def discover(channel: NetlinkChannel): OvsNetlinkFamilies = {
        val broker = new NetlinkRequestBroker(
            new NetlinkBlockingWriter(channel), new NetlinkReader(channel),
            1, 2048, BytesUtil.instance.allocate(2048), NanoClock.DEFAULT)
        val pid = channel.getLocalAddress.getPid

        def request[T >: Null](family: String, f: ByteBuffer => T): T = {
            val seq = broker.nextSequence()
            GenlProtocol.familyNameRequest(family, NLFlag.REQUEST, pid,
                                           CtrlFamily.Context.GetFamily, broker.get(seq))

            @volatile var res: T = null
            broker.publishRequest(seq, new Observer[ByteBuffer] {
                override def onCompleted(): Unit = { }
                override def onError(e: Throwable): Unit = throw e
                override def onNext(bb: ByteBuffer): Unit = res = f(bb)
            })

            broker.writePublishedRequests()

            while (broker.readReply() == 0) { }

            res
        }

        new OvsNetlinkFamilies(
            request(OpenVSwitch.Datapath.Family, (new DatapathFamily(_))
                    compose CtrlFamily.familyIdDeserializer.deserializeFrom),
            request(OpenVSwitch.Port.Family, (new PortFamily(_))
                    compose CtrlFamily.familyIdDeserializer.deserializeFrom),
            request(OpenVSwitch.Flow.Family, (new FlowFamily(_))
                    compose CtrlFamily.familyIdDeserializer.deserializeFrom),
            request(OpenVSwitch.Packet.Family, (new PacketFamily(_))
                    compose CtrlFamily.familyIdDeserializer.deserializeFrom),
            request[Integer](OpenVSwitch.Datapath.Family,
                    CtrlFamily.mcastGrpDeserializer(OpenVSwitch.Datapath.MCGroup).deserializeFrom),
            request[Integer](OpenVSwitch.Port.Family,
                    CtrlFamily.mcastGrpDeserializer(OpenVSwitch.Port.MCGroup)
                        .deserializeFrom _ andThen { data: Integer =>
                            if (data eq null) {
                                OpenVSwitch.Port.fallbackMCGroup
                            } else {
                                data
                            }
                    }))
    }
}

class OvsNetlinkFamilies(val datapathFamily: DatapathFamily,
                         val portFamily: PortFamily,
                         val flowFamily: FlowFamily,
                         val packetFamily: PacketFamily,
                         val datapathMulticast: Int,
                         val portMulticast: Int) {

    override def toString =
        s"OvsNetlinkFamilies{$datapathFamily, $portFamily, $flowFamily, $packetFamily, " +
        s"Datapath Multicast = $datapathMulticast, Port Multicast = $portMulticast}"
}
