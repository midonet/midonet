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

import org.midonet.netlink.NetlinkRequestContext;

public abstract class OvsBaseContext implements NetlinkRequestContext {
    final short commandFamily;
    final byte command;
    public OvsBaseContext(short familyId, byte command) {
        this.commandFamily = familyId;
        this.command = command;
    }
    public short commandFamily() { return commandFamily; }
    public byte command() { return command; }
    abstract public byte version();

    @Override
    public String toString() {
        return getClass().getSimpleName() + "{" +
               "commandFamily=" + commandFamily +
               ", command=" + command +
               '}';
    }
}
