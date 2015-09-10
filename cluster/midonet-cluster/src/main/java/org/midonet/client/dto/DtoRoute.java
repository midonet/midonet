/*
 * Copyright 2014 Midokura SARL
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

package org.midonet.client.dto;

import java.net.URI;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class DtoRoute {
    public static final String Normal = "Normal";
    public static final String BlackHole = "BlackHole";
    public static final String Reject = "Reject";

    private UUID id = null;
    private UUID routerId = null;
    private String srcNetworkAddr = null;
    private int srcNetworkLength;
    private String dstNetworkAddr = null;
    private int dstNetworkLength;
    private UUID nextHopPort = null;
    private String nextHopGateway = null;
    private int weight;
    private String attributes;
    private String type;
    private URI uri;

    public URI getUri() {
        return uri;
    }

    public void setUri(URI uri) {
        this.uri = uri;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getRouterId() {
        return routerId;
    }

    public void setRouterId(UUID routerId) {
        this.routerId = routerId;
    }

    public String getSrcNetworkAddr() {
        return srcNetworkAddr;
    }

    public void setSrcNetworkAddr(String srcNetworkAddr) {
        this.srcNetworkAddr = srcNetworkAddr;
    }

    public int getSrcNetworkLength() {
        return srcNetworkLength;
    }

    public void setSrcNetworkLength(int srcNetworkLength) {
        this.srcNetworkLength = srcNetworkLength;
    }

    public String getDstNetworkAddr() {
        return dstNetworkAddr;
    }

    public void setDstNetworkAddr(String dstNetworkAddr) {
        this.dstNetworkAddr = dstNetworkAddr;
    }

    public int getDstNetworkLength() {
        return dstNetworkLength;
    }

    public void setDstNetworkLength(int dstNetworkLength) {
        this.dstNetworkLength = dstNetworkLength;
    }

    public UUID getNextHopPort() {
        return nextHopPort;
    }

    public void setNextHopPort(UUID nextHopPort) {
        this.nextHopPort = nextHopPort;
    }

    public String getNextHopGateway() {
        return nextHopGateway;
    }

    public void setNextHopGateway(String nextHopGateway) {
        this.nextHopGateway = nextHopGateway;
    }

    public int getWeight() {
        return weight;
    }

    public void setWeight(int weight) {
        this.weight = weight;
    }

    public String getAttributes() {
        return attributes;
    }

    public void setAttributes(String attributes) {
        this.attributes = attributes;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public static String getNormal() {
        return Normal;
    }

    public static String getBlackhole() {
        return BlackHole;
    }

    public static String getReject() {
        return Reject;
    }

    @Override
    public String toString() {
        return "DtoRoute{" +
                "id=" + id +
                ", routerId=" + routerId +
                ", srcNetworkAddr='" + srcNetworkAddr + '\'' +
                ", srcNetworkLength=" + srcNetworkLength +
                ", dstNetworkAddr='" + dstNetworkAddr + '\'' +
                ", dstNetworkLength=" + dstNetworkLength +
                ", nextHopPort=" + nextHopPort +
                ", nextHopGateway='" + nextHopGateway + '\'' +
                ", weight=" + weight +
                ", attributes='" + attributes + '\'' +
                ", type='" + type + '\'' +
                ", uri=" + uri +
                '}';
    }
}
