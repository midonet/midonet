/*
 * Copyright 2011 Midokura Europe SARL
 */

package org.midonet.client.dto;

import javax.xml.bind.annotation.XmlRootElement;
import java.net.URI;

@XmlRootElement
public class DtoApplication {
    private String version;
    private URI uri;
    private URI hosts;
    private URI tunnelZones;
    private URI bridges;
    private URI vlanBridges;
    private URI chains;
    private URI metricsFilter;
    private URI metricsQuery;
    private URI portGroups;
    private URI routers;
    private URI traceConditions;
    private URI traces;
    private URI writeVersion;
    private URI systemState;
    private URI tenants;
    private String adRouteTemplate;
    private String bgpTemplate;
    private String bridgeTemplate;
    private String vlanBridgeTemplate;
    private String chainTemplate;
    private String ruleTemplate;
    private String hostTemplate;
    private String portTemplate;
    private String portGroupTemplate;
    private String routeTemplate;
    private String routerTemplate;
    private String tunnelZoneTemplate;
    private String traceConditionTemplate;
    private String traceTemplate;
    private String tenantTemplate;

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

    public URI getTenants() {
        return tenants;
    }

    public void setTenants(URI tenants) {
        this.tenants = tenants;
    }

    public URI getBridges() {
        return bridges;
    }

    public void setBridges(URI bridges) {
        this.bridges = bridges;
    }

    public URI getVlanBridges() {
        return vlanBridges;
    }

    public void setVlanBridges(URI vlanBridges) {
        this.vlanBridges = vlanBridges;
    }

    public URI getChains() {
        return chains;
    }

    public void setChains(URI chains) {
        this.chains = chains;
    }

    public URI getMetricsFilter() {
        return metricsFilter;
    }

    public void setMetricsFilter(URI metricsFilter) {
        this.metricsFilter = metricsFilter;
    }

    public URI getMetricsQuery() {
        return metricsQuery;
    }

    public void setMetricsQuery(URI metricsQuery) {
        this.metricsQuery = metricsQuery;
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

    public URI getTraceConditions() {
        return traceConditions;
    }

    public void setTraceConditions(URI traceConditions) {
        this.traceConditions = traceConditions;
    }

    public URI getTraces() {
        return traces;
    }

    public void setTraces(URI traces) {
        this.traces = traces;
    }

    public URI getWriteVersion() {
        return writeVersion;
    }

    public void setWriteVersion(URI writeVersion) {
        this.writeVersion = writeVersion;
    }

    public URI getSystemState() {
        return this.systemState;
    }

    public void setSystemState(URI systemState) {
        this.systemState = systemState;
    }

    public String getAdRouteTemplate() {
        return adRouteTemplate;
    }

    public void setAdRoute(String adRouteTemplate) {
        this.adRouteTemplate = adRouteTemplate;
    }

    public String getBgpTemplate() {
        return bgpTemplate;
    }

    public void setBgpTemplate(String bgpTemplate) {
        this.bgpTemplate = bgpTemplate;
    }

    public String getVlanBridgeTemplate() {
        return vlanBridgeTemplate;
    }

    public String getBridgeTemplate() {
        return bridgeTemplate;
    }

    public void setVlanBridgeTemplate(String template) {
        this.vlanBridgeTemplate = template;
    }

    public void setBridgeTemplate(String bridgeTemplate) {
        this.bridgeTemplate = bridgeTemplate;
    }

    public String getChainTemplate() {
        return chainTemplate;
    }

    public void setChainTemplate(String chainTemplate) {
        this.chainTemplate = chainTemplate;
    }

    public String getRuleTemplate() {
        return ruleTemplate;
    }

    public void setRuleTemplate(String ruleTemplate) {
        this.ruleTemplate = ruleTemplate;
    }

    public String getHostTemplate() {
        return hostTemplate;
    }

    public void setHostTemplate(String hostTemplate) {
        this.hostTemplate = hostTemplate;
    }

    public String getPortTemplate() {
        return portTemplate;
    }

    public void setPortTemplate(String portTemplate) {
        this.portTemplate = portTemplate;
    }

    public String getPortGroupTemplate() {
        return portGroupTemplate;
    }

    public void setPortGroupTemplate(String portGroupTemplate) {
        this.portGroupTemplate = portGroupTemplate;
    }

    public String getRouteTemplate() {
        return routeTemplate;
    }

    public void setRoute(String routeTemplate) {
        this.routeTemplate = routeTemplate;
    }

    public String getRouterTemplate() {
        return routerTemplate;
    }

    public void setRouterTemplate(String routerTemplate) {
        this.routerTemplate = routerTemplate;
    }

    public String getTunnelZoneTemplate() {
        return tunnelZoneTemplate;
    }

    public void setTunnelZoneTemplate(String tunnelZoneTemplate) {
        this.tunnelZoneTemplate = tunnelZoneTemplate;
    }

    public String getTraceConditionTemplate() {
        return traceConditionTemplate;
    }

    public void setTraceConditionTemplate(String traceConditionTemplate) {
        this.traceConditionTemplate = traceConditionTemplate;
    }

    public String getTraceTemplate() {
        return traceTemplate;
    }

    public void setTraceTemplate(String traceTemplate) {
        this.traceTemplate = traceTemplate;
    }

    public String getTenantTemplate() {
        return tenantTemplate;
    }

    public void setTenantTemplate(String tenantTemplate) {
        this.tenantTemplate = tenantTemplate;
    }
}
