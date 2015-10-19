/**
*    Copyright 2011, Big Switch Networks, Inc.
*    Originally created by David Erickson, Stanford University
*
*    Licensed under the Apache License, Version 2.0 (the "License"); you may
*    not use this file except in compliance with the License. You may obtain
*    a copy of the License at
*
*         http://www.apache.org/licenses/LICENSE-2.0
*
*    Unless required by applicable law or agreed to in writing, software
*    distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
*    WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
*    License for the specific language governing permissions and limitations
*    under the License.
**/

package org.midonet.packets;

import java.nio.ByteBuffer;

/**
*
* @author David Erickson (daviderickson@cs.stanford.edu)
*/
public interface IPacket {
    int length();

    IPacket getPayload();

    int getPayloadLength();

    IPacket setPayload(IPacket packet);

    IPacket getParent();

    IPacket setParent(IPacket packet);

    /**
     * Sets all payloads parent packet if applicable, then serializes this
     * packet and all payloads
     * @return a byte[] containing this packet and payloads
     */
    byte[] serialize();

    /**
     * Deserializes this packet layer and all possible payloads
     * @param bb  ByteBuffer of the data to deserialize.
     * @return the deserialized data
     */
    IPacket deserialize(ByteBuffer bb) throws MalformedPacketException;
}
