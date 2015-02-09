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

package org.midonet.cluster.cli.commands.objects;

import org.midonet.cluster.data.ZoomEnum;
import org.midonet.cluster.data.ZoomEnumValue;
import org.midonet.cluster.models.Topology;

@ZoomEnum(clazz = Topology.Host.Interface.DpPortType.class)
public enum DpPortType {
    @ZoomEnumValue(value = "NET_DEV_DP")
    NET_DEV,
    @ZoomEnumValue(value = "INTERNAL_DP")
    INTERNAL,
    @ZoomEnumValue(value = "GRE_DP")
    GRE,
    @ZoomEnumValue(value = "VXLAN_DP")
    VXLAN,
    @ZoomEnumValue(value = "GRE64_DP")
    GRE64,
    @ZoomEnumValue(value = "LISP_DP")
    LISP
}
