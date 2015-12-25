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
package org.midonet.cluster.rest_api.neutron.models;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;

import org.midonet.cluster.data.ZoomClass;
import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.data.ZoomObject;
import org.midonet.cluster.models.Neutron;
import org.midonet.packets.IPv4Addr;
import org.midonet.cluster.util.IPAddressUtil;
import org.midonet.cluster.util.MACUtil;
import org.midonet.packets.MAC;

@ZoomClass(clazz = Neutron.RemoteMacEntry.class)
public class RemoteMacEntry extends ZoomObject {

    @ZoomField(name = "id")
    public UUID id;

    @JsonProperty("device_id")
    @ZoomField(name = "device_id")
    public UUID deviceId;

    @JsonProperty("vtep_address")
    @ZoomField(name = "vtep_address", converter = IPAddressUtil.Converter.class)
    public IPv4Addr vtepAddress;

    @JsonProperty("mac_address")
    @ZoomField(name = "mac_address", converter = MACUtil.Converter.class)
    public MAC macAddress;

    @JsonProperty("segmentation_id")
    @ZoomField(name = "segmentation_id")
    public Integer segmentationId;

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        RemoteMacEntry that = (RemoteMacEntry) o;
        return Objects.equal(id, that.id) &&
               Objects.equal(deviceId, that.deviceId) &&
               Objects.equal(vtepAddress, that.vtepAddress) &&
               Objects.equal(macAddress, that.macAddress) &&
               Objects.equal(segmentationId, that.segmentationId);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
            .omitNullValues()
            .add("id", id)
            .add("deviceId", deviceId)
            .add("vtepAddress", vtepAddress)
            .add("macAddress", macAddress)
            .add("segmentationId", segmentationId)
            .toString();
    }
}
