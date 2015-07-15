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

package org.midonet.cluster.data.storage.state_table

import java.util

import org.apache.kafka.common.serialization.{StringSerializer, Serializer}

import org.midonet.cluster.data.storage.state_table.MacTableMergedMap.MACOpinion

class MACOpinionEncoder() extends Serializer[MACOpinion] {
    val stringEncoder = new StringSerializer()

    override def close(): Unit = stringEncoder.close()
    override def configure(configs: util.Map[String, _],
                           isKey: Boolean): Unit = {}

    override def serialize(topic: String, opinion: MACOpinion)
    : Array[Byte] = {
        val strBuffer = new StringBuffer()
        if (opinion._1 eq null) {
            throw new NullPointerException("Can't serialize null mac")
        }
        if (opinion._3 eq null) {
            throw new NullPointerException("Can't serialize null owner")
        }
        strBuffer.append(opinion._1.toString)
        strBuffer.append("/")
        if (opinion._2 ne null) {
            val portId = opinion._2.id
            val ts = opinion._2.ts
            strBuffer.append(portId + ":" + ts)
        } else {
            strBuffer.append("null")
        }
        strBuffer.append("/")
        strBuffer.append(opinion._3)
        stringEncoder.serialize(topic, strBuffer.toString)
    }
}
