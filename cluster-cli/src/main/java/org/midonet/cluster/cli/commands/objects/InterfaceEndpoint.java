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

@ZoomEnum(clazz = Topology.Host.Interface.Endpoint.class)
public enum InterfaceEndpoint {
    @ZoomEnumValue(value = "DATAPATH_EP")
    DATAPATH,
    @ZoomEnumValue(value = "PHYSICAL_EP")
    PHYSICAL,
    @ZoomEnumValue(value = "VM_EP")
    VM,
    @ZoomEnumValue(value = "GRE_EP")
    GRE,
    @ZoomEnumValue(value = "CAPWAP_EP")
    CAPWAP,
    @ZoomEnumValue(value = "LOCALHOST_EP")
    LOCALHOST,
    @ZoomEnumValue(value = "TUNTAP_EP")
    TUNTAP,
    @ZoomEnumValue(value = "UNKNOWN_EP")
    UNKNOWN
}
