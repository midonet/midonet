/*
 * Copyright 2011 Midokura Japan
 */

package org.midonet.client.dto;

import com.google.common.base.Objects;

import javax.xml.bind.annotation.XmlRootElement;
import java.net.URI;
import java.util.UUID;

@XmlRootElement
public class DtoBridge {
    private UUID id;
    private String name;
    private String tenantId;
    private UUID inboundFilterId;
    private UUID outboundFilterId;
    private URI inboundFilter;
    private URI outboundFilter;
    private URI uri;
    private URI ports;
    private URI peerPorts;
    private URI macTable;
    private URI arpTable;
    private URI dhcpSubnets;
    private URI dhcpSubnet6s;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public UUID getInboundFilterId() {
        return inboundFilterId;
    }

    public void setInboundFilterId(UUID inboundFilterId) {
        this.inboundFilterId = inboundFilterId;
    }

    public UUID getOutboundFilterId() {
        return outboundFilterId;
    }

    public void setOutboundFilterId(UUID outboundFilterId) {
        this.outboundFilterId = outboundFilterId;
    }

    public URI getInboundFilter() {
        return inboundFilter;
    }

    public void setInboundFilter(URI inboundFilter) {
        this.inboundFilter = inboundFilter;
    }

    public URI getOutboundFilter() {
        return outboundFilter;
    }

    public void setOutboundFilter(URI outboundFilter) {
        this.outboundFilter = outboundFilter;
    }

    public URI getUri() {
        return uri;
    }

    public void setUri(URI uri) {
        this.uri = uri;
    }

    public URI getPorts() {
        return ports;
    }

    public void setPorts(URI ports) {
        this.ports = ports;
    }

    public URI getPeerPorts() {
        return peerPorts;
    }

    public void setPeerPorts(URI peerPorts) {
        this.peerPorts = peerPorts;
    }

    public URI getArpTable() {
        return arpTable;
    }

    public void setArpTable(URI arpTable) {
        this.arpTable = arpTable;
    }

    public URI getMacTable() {
        return macTable;
    }

    public void setMacTable(URI macTable) {
        this.macTable = macTable;
    }

    public URI getDhcpSubnets() {
        return dhcpSubnets;
    }

    public void setDhcpSubnets(URI dhcpSubnets) {
        this.dhcpSubnets = dhcpSubnets;
    }

    public URI getDhcpSubnet6s() {
        return dhcpSubnet6s;
    }

    public void setDhcpSubnet6s(URI dhcpSubnet6s) {
        this.dhcpSubnet6s = dhcpSubnet6s;
    }

    @Override
    public boolean equals(Object other) {

        if (other == this) {
            return true;
        }

        if (other == null || getClass() != other.getClass()) {
            return false;
        }

        DtoBridge otherBridge = (DtoBridge) other;
        if (!Objects.equal(this.id, otherBridge.getId())) {
            return false;
        }

        if (!Objects.equal(this.name, otherBridge.getName())) {
            return false;
        }

        if (!Objects.equal(this.tenantId, otherBridge.getTenantId())) {
            return false;
        }

        if (!Objects.equal(
                this.inboundFilterId, otherBridge.getInboundFilterId())) {
            return false;
        }

        if (!Objects.equal(
                this.inboundFilter, otherBridge.getInboundFilter())) {
            return false;
        }

        if (!Objects.equal(
                this.outboundFilterId, otherBridge.getOutboundFilterId())) {
            return false;
        }

        if (!Objects.equal(
                this.outboundFilter, otherBridge.getInboundFilter())) {
            return false;
        }

        if (!Objects.equal(this.uri, otherBridge.getUri())) {
            return false;
        }

        if (!Objects.equal(this.ports, otherBridge.getPorts())) {
            return false;
        }

        if (!Objects.equal(this.peerPorts, otherBridge.getPeerPorts())) {
            return false;
        }

        if (!Objects.equal(this.macTable, otherBridge.getMacTable())) {
            return false;
        }

        if (!Objects.equal(this.arpTable, otherBridge.getArpTable())) {
            return false;
        }

        if (!Objects.equal(this.dhcpSubnet6s, otherBridge.getDhcpSubnet6s())) {
            return false;
        }

        if (!Objects.equal(this.dhcpSubnets, otherBridge.getDhcpSubnets())) {
            return false;
        }

        return true;
    }
}
