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

package org.midonet.midolman.state.l4lb;

import org.midonet.cluster.data.ZoomEnum;
import org.midonet.cluster.data.ZoomEnumValue;
import org.midonet.cluster.models.Commons;

@ZoomEnum(clazz = Commons.LBStatus.class)
public enum LBStatus {
    @ZoomEnumValue(value = "ACTIVE")
    ACTIVE,
    @ZoomEnumValue(value = "INACTIVE")
    INACTIVE,
    @ZoomEnumValue(value = "MONITORED")
    MONITORED,
    // The API sets a V2 pool member's status to NO_MONITOR when its actual
    // status cannot be obtained from a health monitor. It has no protobuf
    // equivalent.
    NO_MONITOR;

    public static LBStatus fromProto(Commons.LBStatus proto) {
        return LBStatus.valueOf(proto.toString());
    }

    public Commons.LBStatus toProto() {
        return Commons.LBStatus.valueOf(toString());
    }
}
