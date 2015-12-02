package org.midonet.cluster.rest_api.neutron.models;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;

import org.midonet.cluster.data.ZoomClass;
import org.midonet.cluster.data.ZoomEnum;
import org.midonet.cluster.data.ZoomEnumValue;
import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.data.ZoomObject;
import org.midonet.cluster.models.Neutron;
import org.midonet.cluster.util.IPSubnetUtil;

@ZoomClass(clazz = Neutron.IPSecSiteConnection.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public class IPSecSiteConnection extends ZoomObject {

    @ZoomField(name = "id")
    public UUID id;

    @JsonProperty("tenant_id")
    @ZoomField(name = "tenant_id")
    public String tenantId;

    @ZoomField(name = "description")
    public String description;

    @ZoomField(name = "name")
    public String name;

    @JsonProperty("apdmin_state_up")
    @ZoomField(name = "admin_state_up")
    public Boolean adminStateUp;

    @JsonProperty("peer_address")
    @ZoomField(name = "peer_address")
    public String peerAddress;

    @JsonProperty("peer_id")
    @ZoomField(name = "peer_id")
    public String peerId;

    @JsonProperty("peer_cidrs")
    @ZoomField(name = "peer_cidrs", converter = IPSubnetUtil.Converter.class)
    public List<String> peerCidrs;

    @JsonProperty("local_cidrs")
    @ZoomField(name = "local_cidrs", converter = IPSubnetUtil.Converter.class)
    public List<String> localCidrs;

    @JsonProperty("route_mode")
    @ZoomField(name = "route_mode")
    public RouteMode routeMode;

    @ZoomField(name = "mtu")
    public Integer mtu;

    @ZoomField(name = "initiator")
    public Initiator initiator;

    @JsonProperty("auth_mode")
    @ZoomField(name = "auth_mode")
    public AuthMode authMode;

    @ZoomField(name = "psk")
    public String psk;

    @ZoomField(name = "status")
    public Status status;

    @JsonProperty("dpd_action")
    @ZoomField(name = "dpd_action")
    public DpdAction dpdAction;

    @JsonProperty("dpd_interval")
    @ZoomField(name = "dpd_interval")
    public Integer dpdInterval;

    @JsonProperty("dpd_timeout")
    @ZoomField(name = "dpd_timeout")
    public Integer dpdTimeout;

    @JsonProperty("vpnservice_id")
    @ZoomField(name = "vpnservice_id")
    public UUID vpnServiceId;

    @JsonProperty("ikepolicy")
    @ZoomField(name = "ikepolicy")
    public IkePolicy ikePolicy;

    @JsonProperty("ipsecpolicy")
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
        @ZoomEnumValue("PSK") PSK;

        @JsonCreator
        @SuppressWarnings("unused")
        public static AuthMode forValue(String v) {
            return valueOf(convertFromIpsecString(v));
        }
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
        @ZoomEnumValue("STATIC") STATIC;

        @JsonCreator
        @SuppressWarnings("unused")
        public static RouteMode forValue(String v) {
            return valueOf(convertFromIpsecString(v));
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        IPSecSiteConnection that = (IPSecSiteConnection) o;

        return Objects.equals(id, that.id) &&
               Objects.equals(tenantId, that.tenantId) &&
               Objects.equals(name, that.name) &&
               Objects.equals(peerAddress, that.peerAddress) &&
               Objects.equals(peerId, that.peerId) &&
               Objects.equals(peerCidrs, that.peerCidrs) &&
               Objects.equals(localCidrs, that.localCidrs) &&
               Objects.equals(routeMode, that.routeMode) &&
               Objects.equals(mtu, that.mtu) &&
               initiator == that.initiator &&
               authMode == that.authMode &&
               Objects.equals(psk, that.psk) &&
               adminStateUp == that.adminStateUp &&
               Objects.equals(status, that.status) &&
               dpdAction == that.dpdAction &&
               Objects.equals(dpdInterval, that.dpdInterval) &&
               Objects.equals(dpdTimeout, that.dpdTimeout) &&
               Objects.equals(ikePolicy, that.ikePolicy) &&
               Objects.equals(ipsecPolicy, that.ipsecPolicy);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
            .omitNullValues()
            .add("id", id)
            .add("tenantId", tenantId)
            .add("name", name)
            .add("peerAddress", peerAddress)
            .add("peerId", peerId)
            .add("peerCidrs", peerCidrs)
            .add("localCidrs", localCidrs)
            .add("routeMode", routeMode)
            .add("mtu", mtu)
            .add("initiator", initiator)
            .add("authMode", authMode)
            .add("psk", psk)
            .add("admin_state_up", adminStateUp)
            .add("status", status)
            .add("dpdAction", dpdAction)
            .add("dpdInterval", dpdInterval)
            .add("dpdTimeout", dpdTimeout)
            .add("vpnServiceId", vpnServiceId)
            .add("ikePolicy", ikePolicy)
            .add("ipsecPolicy", ipsecPolicy)
            .toString();
    }

    /*
     * Takes any string and converts to uppercase, and
     * replaces all hyphens with underscores. This is a protobuf compatible
     * version of whatever is sent from neutron.
     */
    public static String convertFromIpsecString(String str) {
        if (str == null) {
            return null;
        }
        return str.replace('-', '_').toUpperCase();
    }
}
