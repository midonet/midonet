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
package org.midonet.odp.family;

import org.midonet.odp.OpenVSwitch;

/**
 * Abstraction for the NETLINK OvsPacket family of commands and attributes.
 */
public class PacketFamily {

    public final short familyId;

    public final OvsBaseContext contextMiss;
    public final OvsBaseContext contextExec;
    public final OvsBaseContext contextAction;

    public PacketFamily(short familyId) {
        this.familyId = familyId;
        contextMiss = new PacketContext(familyId, OpenVSwitch.Packet.Cmd.Miss);
        contextExec = new PacketContext(familyId, OpenVSwitch.Packet.Cmd.Exec);
        contextAction = new PacketContext(familyId, OpenVSwitch.Packet.Cmd.Action);
    }

    private static class PacketContext extends OvsBaseContext {
        public PacketContext(short familyId, byte command) {
            super(familyId, command);
        }
        @Override
        public byte version() { return OpenVSwitch.Packet.version; }
    }

    @Override
    public String toString() {
        return "PacketFamily{" +
               "familyId=" + familyId +
               ", contextMiss=" + contextMiss +
               ", contextExec=" + contextExec +
               ", contextAction=" + contextAction +
               '}';
    }
}
