package org.midonet.cluster.rest_api.neutron.models;

import java.util.List;
import java.util.UUID;

import org.midonet.cluster.data.ZoomClass;
import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.models.Neutron;
import org.midonet.cluster.util.IPAddressUtil;
import org.midonet.cluster.util.UUIDUtil;
import org.midonet.packets.IPAddr;

@ZoomClass(clazz = Neutron.VPNService.class)
public class VPNService {
    @ZoomField(name = "id", converter = UUIDUtil.Converter.class)
    public UUID id;

    @ZoomField(name = "id")
    public String name;

    @ZoomField(name = "id")
    public String description;

    @ZoomField(name = "id")
    public Boolean admin_state_up;

    @ZoomField(name = "id")
    public String tenantId;

    @ZoomField(name = "id", converter = UUIDUtil.Converter.class)
    public UUID routerId;

    @ZoomField(name = "id", converter = UUIDUtil.Converter.class)
    public UUID subnetId;

    @ZoomField(name = "id")
    public String status;

    @ZoomField(name = "id", converter = IPAddressUtil.Converter.class)
    public List<IPAddr> externalIps;
}

