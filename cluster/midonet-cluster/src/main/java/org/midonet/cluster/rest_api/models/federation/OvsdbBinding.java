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

package org.midonet.cluster.rest_api.models.federation;

import java.util.UUID;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;

import org.midonet.cluster.data.ZoomClass;
import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.data.ZoomObject;
import org.midonet.cluster.models.Federation;
import org.midonet.cluster.util.UUIDUtil.Converter;

import static java.util.Objects.hash;

@ZoomClass(clazz = Federation.VxlanSegment.OvsdbBinding.class)
public class OvsdbBinding extends ZoomObject {

    @ZoomField(name = "ovsdb_vtep_id", converter = Converter.class)
    public UUID ovsdbVtepId;

    @NotNull
    @ZoomField(name = "port_name")
    public String portName;

    @Min(0)
    @Max(4095)
    @ZoomField(name = "vlan_id")
    public short vlanId;

    @Override
    public int hashCode() {
        return hash(ovsdbVtepId, portName, vlanId);
    }
}
