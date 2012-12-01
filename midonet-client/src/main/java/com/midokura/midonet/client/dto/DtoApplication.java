/*
 * Copyright 2011 Midokura Europe SARL
 */

package com.midokura.midonet.client.dto;

import javax.xml.bind.annotation.XmlRootElement;
import java.net.URI;

@XmlRootElement
public class DtoApplication {
    private String version;
    private URI uri;
    private URI hosts;
    private URI tunnelZones;
    private URI bridges;
    private URI chains;
    private URI portGroups;
    private URI routers;
    private String adRoute;
    private String bgp;
    private String bridge;
    private String chain;
    private String rule;
    private String host;
    private String port;
    private String portGroup;
    private String route;
    private String router;
    private String tunnelZone;

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public URI getUri() {
        return uri;
    }

    public void setUri(URI uri) {
        this.uri = uri;
    }

    public URI getBridges() {
        return bridges;
    }

    public void setBridges(URI bridges) {
        this.bridges = bridges;
    }

    public URI getChains() {
        return chains;
    }

    public void setChains(URI chains) {
        this.chains = chains;
    }

    public URI getPortGroups() {
        return portGroups;
    }

    public void setPortGroups(URI portGroups) {
        this.portGroups = portGroups;
    }

    public URI getRouters() {
        return routers;
    }

    public void setRouters(URI routers) {
        this.routers = routers;
    }

    public URI getHosts() {
        return hosts;
    }

    public void setHosts(URI hosts) {
        this.hosts = hosts;
    }

    public URI getTunnelZones() {
        return tunnelZones;
    }

    public void setTunnelZones(URI tunnelZones) {
        this.tunnelZones = tunnelZones;
    }

    public String getAdRoute() {
        return adRoute;
    }

    public void setAdRoute(String adRoute) {
        this.adRoute = adRoute;
    }

    public String getBgp() {
        return bgp;
    }

    public void setBgp(String bgp) {
        this.bgp = bgp;
    }

    public String getBridge() {
        return bridge;
    }

    public void setBridge(String bridge) {
        this.bridge = bridge;
    }

    public String getChain() {
        return chain;
    }

    public void setChain(String chain) {
        this.chain = chain;
    }

    public String getRule() {
        return rule;
    }

    public void setRule(String rule) {
        this.rule = rule;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public String getPort() {
        return port;
    }

    public void setPort(String port) {
        this.port = port;
    }

    public String getPortGroup() {
        return portGroup;
    }

    public void setPortGroup(String portGroup) {
        this.portGroup = portGroup;
    }

    public String getRoute() {
        return route;
    }

    public void setRoute(String route) {
        this.route = route;
    }

    public String getRouter() {
        return router;
    }

    public void setRouter(String router) {
        this.router = router;
    }

    public String getTunnelZone() {
        return tunnelZone;
    }

    public void setTunnelZone(String tunnelZone) {
        this.tunnelZone = tunnelZone;
    }
}
