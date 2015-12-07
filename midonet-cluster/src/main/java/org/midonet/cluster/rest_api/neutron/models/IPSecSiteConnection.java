package org.midonet.cluster.rest_api.neutron.models;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import org.midonet.cluster.data.ZoomClass;
import org.midonet.cluster.data.ZoomEnum;
import org.midonet.cluster.data.ZoomEnumValue;
import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.models.Neutron;
import org.midonet.cluster.rest_api.models.UriResource;
import org.midonet.packets.IPSubnet;

import javax.ws.rs.core.UriBuilder;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@ZoomClass(clazz = Neutron.IPSecSiteConnection.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public class IPSecSiteConnection extends UriResource {

    @ZoomField(name = "id")
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

    @ZoomField(name = "local_cidrs")
    public List<IPSubnet> localCidrs;

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

    @ZoomField(name = "admin_state_up")
    public Boolean admin_state_up;

    @ZoomField(name = "status")
    public Status status;

    @ZoomField(name = "dpd_action")
    public DpdAction dpdAction;

    @ZoomField(name = "dpd_interval")
    public Integer dpdInterval;

    @ZoomField(name = "dpd_timeout")
    public Integer dpdTimeout;

    @ZoomField(name = "vpn_service_id")
    public UUID vpnServiceId;

    @ZoomField(name = "ike_policy_id")
    public UUID ikePolicyId;

    @ZoomField(name = "ipsec_policy_id")
    public UUID ipsecPolicyId;

    @ZoomField(name = "ikepolicy")
    public IKEPolicy ikePolicy;

    @ZoomField(name = "ipsecpolicy")
    public IPSecPolicy ipsecPolicy;

    @ZoomEnum(clazz = Neutron.IPSecSiteConnection.Status.class)
    public enum Status {
        @ZoomEnumValue("ACTIVE") ACTIVE,
        @ZoomEnumValue("DOWN") DOWN,
        @ZoomEnumValue("BUILD") BUILD,
        @ZoomEnumValue("ERROR") ERROR,
        @ZoomEnumValue("PENDING_CREATE") PENDING_CREATE,
        @ZoomEnumValue("PENDING_UPDATE") PENDING_UPDATE,
        @ZoomEnumValue("PENDING_DELETE") PENDING_DELETE;

        @JsonCreator
        @SuppressWarnings("unused")
        public static Status forValue(String v) {
            return valueOf(convertFromIpsecString(v));
        }
    }

    @ZoomEnum(clazz = Neutron.IPSecSiteConnection.DpdAction.class)
    public enum DpdAction {
        @ZoomEnumValue("CLEAR") CLEAR,
        @ZoomEnumValue("HOLD") HOLD,
        @ZoomEnumValue("RESTART") RESTART,
        @ZoomEnumValue("DISABLED") DISABLED,
        @ZoomEnumValue("RESTART_BY_PEER") RESTART_BY_PEER;

        @JsonCreator
        @SuppressWarnings("unused")
        public static DpdAction forValue(String v) {
            return valueOf(convertFromIpsecString(v));
        }
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
        @ZoomEnumValue("RESPONSE_ONLY") RESPONSE_ONLY;

        @JsonCreator
        @SuppressWarnings("unused")
        public static Initiator forValue(String v) {
            return valueOf(convertFromIpsecString(v));
        }
    }

    // TODO: this field and the enum is pointless, leave out and add whenever
    // needed?
    @ZoomEnum(clazz = Neutron.IPSecSiteConnection.RouteMode.class)
    public enum RouteMode {
        @ZoomEnumValue("STATIC") STATIC
    }

    @Override
    public URI getUri() {
        if (id == null) {
            return null;
        }
        return UriBuilder.fromUri(getBaseUri())
                         .path("neutron")
                         .path("ipsec_site_conns")
                         .path(id.toString()).build();
    }

    /*
     * Takes any string and converts to uppercase, and
     * replaces all hyphens with underscores. This is a protobuf compatible
     * version of whatever is sent from neutron.
     */
    public static String convertFromIpsecString(String str) {
        if (str == null) return null;
        try {
            str = str.replace('-', '_');
            return str.toUpperCase();
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
