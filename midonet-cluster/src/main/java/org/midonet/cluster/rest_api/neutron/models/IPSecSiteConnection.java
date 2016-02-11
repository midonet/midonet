/*
 * Copyright 2016 Midokura SARL
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

    @JsonProperty("admin_state_up")
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
            return valueOf(normalizeIpSecEnumString(v));
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
            return valueOf(normalizeIpSecEnumString(v));
        }
    }

    // TODO: this field and the enum is pointless, leave out and add whenever
    // needed?
    @ZoomEnum(clazz = Neutron.IPSecSiteConnection.AuthMode.class)
    public enum AuthMode {
        @ZoomEnumValue("PSK") PSK;

        @JsonCreator
        @SuppressWarnings("unused")
        public static AuthMode forValue(String v) {
            return valueOf(normalizeIpSecEnumString(v));
        }
    }

    @ZoomEnum(clazz = Neutron.IPSecSiteConnection.Initiator.class)
    public enum Initiator {
        @ZoomEnumValue("BI_DIRECTIONAL") BI_DIRECTIONAL,
        @ZoomEnumValue("RESPONSE_ONLY") RESPONSE_ONLY;

        @JsonCreator
        @SuppressWarnings("unused")
        public static Initiator forValue(String v) {
            return valueOf(normalizeIpSecEnumString(v));
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
            return valueOf(normalizeIpSecEnumString(v));
        }
    }

    @ZoomEnum(clazz = Neutron.IPSecSiteConnection.IPSecPfs.class)
    public enum IPSecPfs {
        @ZoomEnumValue("GROUP2") GROUP2,
        @ZoomEnumValue("GROUP5") GROUP5,
        @ZoomEnumValue("GROUP14") GROUP14;

        @JsonCreator
        @SuppressWarnings("unused")
        public static IPSecPfs forValue(String v) {
            return valueOf(normalizeIpSecEnumString(v));
        }
    }

    @ZoomEnum(clazz = Neutron.IPSecSiteConnection.IPSecAuthAlgorithm.class)
    public enum IPSecAuthAlgorithm {
        @ZoomEnumValue("SHA1")SHA1;

        @JsonCreator
        @SuppressWarnings("unused")
        public static IPSecAuthAlgorithm forValue(String v) {
            return valueOf(IPSecSiteConnection.normalizeIpSecEnumString(v));
        }
    }

    @ZoomEnum(clazz = Neutron.IPSecSiteConnection.IPSecEncryptionAlgorithm.class)
    public enum IPSecEncryptionAlgorithm {
        @ZoomEnumValue("DES_3") DES_3,
        @ZoomEnumValue("AES_128") AES_128,
        @ZoomEnumValue("AES_192") AES_192,
        @ZoomEnumValue("AES_256") AES_256;

        @JsonCreator
        @SuppressWarnings("unused")
        public static IPSecEncryptionAlgorithm forValue(String v) {
            return valueOf(normalizeIpSecEnumString(v));
        }
    }

    @ZoomClass(clazz = Neutron.IPSecSiteConnection.IPSecPolicy.class)
    public static class IPSecPolicy extends ZoomObject {
        @JsonProperty("transform_protocol")
        @ZoomField(name = "transform_protocol")
        public TransformProtocol transformProtocol;

        @JsonProperty("auth_algorithm")
        @ZoomField(name = "auth_algorithm")
        public IPSecAuthAlgorithm authAlgorithm;

        @JsonProperty("encryption_algorithm")
        @ZoomField(name = "encryption_algorithm")
        public IPSecEncryptionAlgorithm encryptionAlgorithm;

        @JsonProperty("encapsulation_mode")
        @ZoomField(name = "encapsulation_mode")
        public EncapsulationMode encapsulationMode;

        @ZoomField(name = "pfs")
        public IPSecPfs pfs;

        @JsonProperty("lifetime_units")
        @ZoomField(name = "lifetime_units")
        public String lifetimeUnits;

        @JsonProperty("lifetime_value")
        @ZoomField(name = "lifetime_value")
        public Integer lifetimeValue;

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            IPSecPolicy that = (IPSecPolicy) o;

            return Objects.equals(transformProtocol, that.transformProtocol) &&
                Objects.equals(authAlgorithm, that.authAlgorithm) &&
                Objects.equals(encryptionAlgorithm, that.encryptionAlgorithm) &&
                Objects.equals(encapsulationMode, that.encapsulationMode) &&
                Objects.equals(lifetimeUnits, that.lifetimeUnits) &&
                Objects.equals(lifetimeValue, that.lifetimeValue) &&
                Objects.equals(pfs, that.pfs);
        }

        @Override
        public int hashCode() {
            return Objects.hash(transformProtocol, authAlgorithm,
                                encryptionAlgorithm, encryptionAlgorithm,
                                lifetimeUnits, lifetimeValue, pfs);
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                .omitNullValues()
                .add("transformProtocol", transformProtocol)
                .add("authAlgorithm", authAlgorithm)
                .add("encryptionAlgorithm", encryptionAlgorithm)
                .add("encapsulationMode", encapsulationMode)
                .add("lifetimeUnits", lifetimeUnits)
                .add("lifetimeValue", lifetimeValue)
                .add("pfs", pfs).toString();
        }

        @ZoomEnum(clazz = Neutron.IPSecSiteConnection.IPSecPolicy.TransformProtocol.class)
        public enum TransformProtocol {
            @ZoomEnumValue("ESP") ESP,
            @ZoomEnumValue("AH") AH,
            @ZoomEnumValue("AH_ESP") AH_ESP;

            @JsonCreator
            @SuppressWarnings("unused")
            public static TransformProtocol forValue(String v) {
                return valueOf(IPSecSiteConnection.normalizeIpSecEnumString(v));
            }
        }

        @ZoomEnum(clazz = Neutron.IPSecSiteConnection.IPSecPolicy.EncapsulationMode.class)
        public enum EncapsulationMode {
            @ZoomEnumValue("TUNNEL") TUNNEL,
            @ZoomEnumValue("TRANSPORT") TRANSPORT;

            @JsonCreator
            @SuppressWarnings("unused")
            public static EncapsulationMode forValue(String v) {
                return valueOf(IPSecSiteConnection.normalizeIpSecEnumString(v));
            }
        }
    }

    @ZoomClass(clazz = Neutron.IPSecSiteConnection.IkePolicy.class)
    public static class IkePolicy extends ZoomObject {
        @JsonProperty("auth_algorithm")
        @ZoomField(name = "auth_algorithm")
        public IPSecAuthAlgorithm authAlgorithm;

        @JsonProperty("encryption_algorithm")
        @ZoomField(name = "encryption_algorithm")
        public IPSecEncryptionAlgorithm encryptionAlgorithm;

        @JsonProperty("phase1_negotiation_mode")
        @ZoomField(name = "phase1_negotiation_mode")
        public Phase1NegotiationMode phase1NegMode;

        @JsonProperty("ike_version")
        @ZoomField(name = "ike_version")
        public IkeVersion ikeVersion;

        @JsonProperty("lifetime_units")
        @ZoomField(name = "lifetime_units")
        public String lifetimeUnits;

        @JsonProperty("lifetime_value")
        @ZoomField(name = "lifetime_value")
        public Integer lifetimeValue;

        @ZoomField(name = "pfs")
        public IPSecPfs pfs;

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            IkePolicy that = (IkePolicy) o;

            return Objects.equals(authAlgorithm, that.authAlgorithm) &&
                Objects.equals(encryptionAlgorithm, that.encryptionAlgorithm) &&
                Objects.equals(phase1NegMode, that.phase1NegMode) &&
                Objects.equals(ikeVersion, that.ikeVersion) &&
                Objects.equals(lifetimeUnits, that.lifetimeUnits) &&
                Objects.equals(lifetimeValue, that.lifetimeValue) &&
                Objects.equals(pfs, that.pfs);
        }

        @Override
        public int hashCode() {
            return Objects.hash(authAlgorithm, encryptionAlgorithm, phase1NegMode,
                                ikeVersion, lifetimeUnits, lifetimeValue, pfs);
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                .omitNullValues()
                .add("authAlgorithm", authAlgorithm)
                .add("encryptionAlgorithm", encryptionAlgorithm)
                .add("phase1NegMode", phase1NegMode)
                .add("ikeVersion", ikeVersion)
                .add("lifetimeUnits", lifetimeUnits)
                .add("lifetimeValue", lifetimeValue)
                .add("pfs", pfs)
                .toString();
        }

        @ZoomEnum(clazz = Neutron.IPSecSiteConnection.IkePolicy.Phase1NegotiationMode.class)
        public enum Phase1NegotiationMode {
            @ZoomEnumValue("MAIN") MAIN;

            @JsonCreator
            @SuppressWarnings("unused")
            public static Phase1NegotiationMode forValue(String v) {
                return valueOf(IPSecSiteConnection.normalizeIpSecEnumString(v));
            }
        }

        @ZoomEnum(clazz = Neutron.IPSecSiteConnection.IkePolicy.IkeVersion.class)
        public enum IkeVersion {
            @ZoomEnumValue("V1") V1,
            @ZoomEnumValue("V2") V2;

            @JsonCreator
            @SuppressWarnings("unused")
            public static IkeVersion forValue(String v) {
                return valueOf(IPSecSiteConnection.normalizeIpSecEnumString(v));
            }
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
               Objects.equals(routeMode, that.routeMode) &&
               Objects.equals(mtu, that.mtu) &&
               initiator == that.initiator &&
               authMode == that.authMode &&
               Objects.equals(psk, that.psk) &&
               adminStateUp == that.adminStateUp &&
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
            .add("dpdAction", dpdAction)
            .add("dpdInterval", dpdInterval)
            .add("dpdTimeout", dpdTimeout)
            .add("vpnServiceId", vpnServiceId)
            .add("ikePolicy", ikePolicy)
            .add("ipsecPolicy", ipsecPolicy)
            .toString();
    }

    /**
     * Returns true iff the ipsec site connection has valid fields, namely that
     * the local and peer cidr lists do not have an entry in common.
     */
    public boolean validate() {
        for (String cidr: localCidrs) {
            if (peerCidrs.contains(cidr)) {
                return false;
            }
        }
        return true;
    }

    /*
     * Takes any string and converts to uppercase, and
     * replaces all hyphens with underscores. This is a protobuf compatible
     * version of whatever is sent from neutron.
     */
    public static String normalizeIpSecEnumString(String str) {
        if (str == null) {
            return null;
        }
        return str.replace('-', '_').toUpperCase();
    }
}
