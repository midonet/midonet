package org.midonet.cluster.rest_api.neutron.models;

import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;

import org.midonet.cluster.data.ZoomClass;
import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.data.ZoomObject;
import org.midonet.cluster.models.Neutron;
import org.midonet.packets.IPv4Addr;

@ZoomClass(clazz = Neutron.RemoteMacEntry.class)
public class RemoteMacEntry extends ZoomObject {

    @ZoomField(name = "name")
    public String name;

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

        return Objects.equals(name, that.name) &&
               Objects.equals(macAddress, that.macAddress) &&
               Objects.equals(vtepAddress, that.vtepAddress) &&
               Objects.equals(segmentationId, that.segmentationId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, macAddress, vtepAddress, segmentationId);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
            .omitNullValues()
            .add("name", name)
            .add("macAddress", macAddress)
            .add("vtepAddress", vtepAddress)
            .add("segmentationId", segmentationId)
            .toString();
    }
}
