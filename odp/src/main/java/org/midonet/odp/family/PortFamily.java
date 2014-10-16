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
 * Abstraction for the NETLINK OvsVPort family of commands and attributes.
 */
public class PortFamily {

    public final OvsBaseContext contextNew;
    public final OvsBaseContext contextDel;
    public final OvsBaseContext contextGet;
    public final OvsBaseContext contextSet;

    public PortFamily(short familyId) {
        contextNew = new PortContext(familyId, OpenVSwitch.Port.Cmd.New);
        contextDel = new PortContext(familyId, OpenVSwitch.Port.Cmd.Del);
        contextGet = new PortContext(familyId, OpenVSwitch.Port.Cmd.Get);
        contextSet = new PortContext(familyId, OpenVSwitch.Port.Cmd.Set);
    }

    private static class PortContext extends OvsBaseContext {
        public PortContext(short familyId, byte command) {
            super(familyId, command);
        }
        @Override
        public byte version() { return OpenVSwitch.Port.version; }
    }
}
