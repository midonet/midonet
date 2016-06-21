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

package org.midonet.services

package object flowstate {

    /**
      * The flow state message will be 8 bytes long, splitted as:
      *
      * +----------------------------------------------------+
      * | Flow state message type (4 bytes)                  |
      * +----------------------------------------------------+
      * | Flow state message size in bytes (4 bytes)         |
      * +----------------------------------------------------+
      * | Data (uuids or flow state message itself)          |
      * |                                                    |
      * | if UUID list -> big endian (msb, lsb)              |
      * |                                                    |
      * +----------------------------------------------------+
      */
    object FlowStateInternalMessageType {
        val FlowStateMessage: Int = 0x01
        val OwnedPortsUpdate: Int = 0x02
    }


    /**
      * Maximum message size to send to the flow state minion. Set to 64k
      * as this is a typical value for the MTU loopback interface. We will reach
      * that limit when the agent has 8192 ports bound considering that UUIDs
      * are 8 bytes long (4096 ports * 16 bytes = 64kB). Actually, it's one less
      * because we already fill 8 bytes for the header.
      */
    val MaxMessageSize = 65536
    val MaxPortIds = (65536 / 16) - 1
    val FlowStateInternalMessageHeaderSize = 8

}

