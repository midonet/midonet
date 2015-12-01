package org.midonet.cluster.rest_api.neutron.models;

import java.util.List;
import java.util.UUID;

import org.midonet.cluster.data.ZoomClass;
import org.midonet.cluster.data.ZoomEnum;
import org.midonet.cluster.data.ZoomEnumValue;
import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.models.Neutron;
import org.midonet.cluster.util.UUIDUtil;
import org.midonet.packets.IPSubnet;

@ZoomClass(clazz = Neutron.IPSecSiteConnection.class)
public class IPSecSiteConnection {

    @ZoomField(name = "id", converter = UUIDUtil.Converter.class)
    public UUID id;

    @ZoomField(name = "tenant_id")
    public String tenantId;

    @ZoomField(name = "name")
    public String name;

    @ZoomField(name = "peer_address")
    public String peerAddress;

    @ZoomField(name = "peer_id")
    public String peerId;

    @ZoomField(name = "peer_cidrs")
    public List<IPSubnet> peerCidrs;

    @ZoomField(name = "route_mode")
    public RouteMode routeMode;

    @ZoomField(name = "mtu")
    public Integer mtu;

    @ZoomField(name = "initiator")
    public Initiator initiator;

    @ZoomField(name = "auth_mode")
    public AuthMode authMode;

    @ZoomField(name = "psk")
    public String psk;

    @ZoomField(name = "actions")
    public List<String> actions;

    @ZoomField(name = "admin_state_up")
    public Boolean admin_state_up;

    @ZoomField(name = "status")
    public Status status;

    @ZoomField(name = "dpd_action")
    public String dpdAction;

    @ZoomField(name = "dpd_interval")
    public Integer dpdInterval;

    @ZoomField(name = "dpd_timeout")
    public Integer dpdTimeout;

    @ZoomField(name = "vpn_service_id", converter = UUIDUtil.Converter.class)
    public UUID vpnServiceId;

    @ZoomField(name = "ike_policy_id", converter = UUIDUtil.Converter.class)
    public UUID ikePolicyId;

    @ZoomField(name = "ipsec_policy_id", converter = UUIDUtil.Converter.class)
    public UUID ipsecPolicyId;

    @ZoomEnum(clazz = Neutron.IPSecSiteConnection.Status.class)
    public enum Status {
        @ZoomEnumValue("ACTIVE") ACTIVE,
        @ZoomEnumValue("DOWN") DOWN,
        @ZoomEnumValue("BUILD") BUILD,
        @ZoomEnumValue("ERROR") ERROR,
        @ZoomEnumValue("PENDING_CREATE") PENDING_CREATE,
        @ZoomEnumValue("PENDING_UPDATE") PENDING_UPDATE,
        @ZoomEnumValue("PENDING_DELETE") PENDING_DELETE
    }

    // TODO: this field and the enum is pointless, leave out and add whenever
    // needed?
    @ZoomEnum(clazz = Neutron.IPSecSiteConnection.AuthMode.class)
    enum AuthMode {
        @ZoomEnumValue("PSK") PSK
    }

    @ZoomEnum(clazz = Neutron.IPSecSiteConnection.Initiator.class)
    enum Initiator {
        @ZoomEnumValue("BI_DIRECTIONAL") BI_DIRECTIONAL,
        @ZoomEnumValue("RESPONSE_ONLY") RESPONSE_ONLY
    }

    // TODO: this field and the enum is pointless, leave out and add whenever
    // needed?
    @ZoomEnum(clazz = Neutron.IPSecSiteConnection.RouteMode.class)
    public enum RouteMode {
        @ZoomEnumValue("STATIC") STATIC
    }
}
