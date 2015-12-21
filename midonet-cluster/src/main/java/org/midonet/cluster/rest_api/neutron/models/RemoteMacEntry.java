package org.midonet.cluster.rest_api.neutron.models;

import java.util.Objects;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;

import org.midonet.cluster.data.ZoomClass;
import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.data.ZoomObject;
import org.midonet.cluster.models.Neutron;
import org.midonet.packets.IPv4Addr;

@ZoomClass(clazz = Neutron.RemoteMacEntry.class)
public class RemoteMacEntry extends ZoomObject {

    @ZoomField(name = "id")
    public UUID id;

    @JsonProperty("mac_address")
    @ZoomField(name = "mac_address")
    public String macAddress;

    @JsonProperty("vtep_address")
    @ZoomField(name = "vtep_address")
    public IPv4Addr vtepAddress;

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

        return Objects.equals(id, that.id) &&
               Objects.equals(macAddress, that.macAddress) &&
               Objects.equals(vtepAddress, that.vtepAddress) &&
               Objects.equals(segmentationId, that.segmentationId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, macAddress, vtepAddress, segmentationId);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
            .omitNullValues()
            .add("id", id)
            .add("macAddress", macAddress)
            .add("vtepAddress", vtepAddress)
            .add("segmentationId", segmentationId)
            .toString();
    }
}
