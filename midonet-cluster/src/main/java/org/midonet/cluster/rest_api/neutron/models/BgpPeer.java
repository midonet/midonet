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

import java.util.Objects;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;

import org.midonet.cluster.data.ZoomClass;
import org.midonet.cluster.data.ZoomEnum;
import org.midonet.cluster.data.ZoomEnumValue;
import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.data.ZoomObject;
import org.midonet.cluster.models.Neutron;
import org.midonet.cluster.util.IPAddressUtil;

@ZoomClass(clazz = Neutron.NeutronBgpPeer.class)
public class BgpPeer extends ZoomObject {
    @ZoomField(name = "id")
    public UUID id;

    @JsonProperty("tenant_id")
    @ZoomField(name = "tenant_id")
    public String tenantId;

    @ZoomField(name = "name")
    public String name;

    @JsonProperty("peer_ip")
    @ZoomField(name = "peer_ip", converter = IPAddressUtil.Converter.class)
    public String peerIp;

    @JsonProperty("remote_as")
    @ZoomField(name = "remote_as")
    public Integer remoteAs;

    @JsonProperty("auth_type")
    @ZoomField(name = "auth_type")
    public AuthType authType;

    @JsonProperty("password")
    @ZoomField(name = "password")
    public String password;

    @JsonProperty("bgp_speaker")
    @ZoomField(name = "bgp_speaker")
    public BgpSpeaker bgpSpeaker;

    @ZoomEnum(clazz = Neutron.NeutronBgpPeer.BgpAuthType.class)
    public enum AuthType {
        @ZoomEnumValue("MD5") MD5;

        @JsonCreator
        @SuppressWarnings("unused")
        public static AuthType forValue(String v) {
            if (v == null) {
                return null;
            }
            return valueOf(v.toUpperCase());
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

        BgpPeer that = (BgpPeer) o;

        return Objects.equals(id, that.id) &&
               Objects.equals(tenantId, that.tenantId) &&
               Objects.equals(name, that.name) &&
               Objects.equals(peerIp, that.peerIp) &&
               Objects.equals(remoteAs, that.remoteAs) &&
               Objects.equals(bgpSpeaker, that.bgpSpeaker);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, tenantId, name, peerIp, remoteAs, bgpSpeaker);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
            .omitNullValues()
            .add("id", id)
            .add("tenantId", tenantId)
            .add("name", name)
            .add("peerIp", peerIp)
            .add("remoteAs", remoteAs)
            .add("bgpSpeaker", bgpSpeaker)
            .toString();
    }
}
