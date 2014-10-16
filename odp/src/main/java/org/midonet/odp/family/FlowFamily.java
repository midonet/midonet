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
 * Abstraction for the NETLINK OvsFlow family of commands and attributes.
 */
public class FlowFamily {

    public final OvsBaseContext contextNew;
    public final OvsBaseContext contextDel;
    public final OvsBaseContext contextGet;
    public final OvsBaseContext contextSet;

    public FlowFamily(short familyId) {
        contextNew = new FlowContext(familyId, OpenVSwitch.Flow.Cmd.New);
        contextDel = new FlowContext(familyId, OpenVSwitch.Flow.Cmd.Del);
        contextGet = new FlowContext(familyId, OpenVSwitch.Flow.Cmd.Get);
        contextSet = new FlowContext(familyId, OpenVSwitch.Flow.Cmd.Set);
    }

    private static class FlowContext extends OvsBaseContext {
        public FlowContext(short familyId, byte command) {
            super(familyId, command);
        }
        @Override
        public byte version() { return OpenVSwitch.Flow.version; }
    }
}
