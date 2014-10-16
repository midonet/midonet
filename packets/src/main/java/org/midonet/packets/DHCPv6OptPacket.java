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

package org.midonet.packets;

import java.nio.ByteBuffer;

/*
 * more specific version of the IPacket interface.
 * Adds functions specific to DHCPv6 options.
 */
public interface DHCPv6OptPacket extends IPacket {

    /*
     * set the DHCPv6 option type.
     */
    public void setCode(short code);

    /*
     * get the DHCPv6 option type.
     */
    public short getCode();

    /*
     * get the DHCPv6 option length.
     */
    public void setLength(short length);

    /*
     * get the DHCPv6 option length.
     */
    public short getLength();

    /*
     * Deserialize just the payload of the option. Everything except for
     * the code and length (option header).
     */
    public void deserializeData(ByteBuffer bb);
}
