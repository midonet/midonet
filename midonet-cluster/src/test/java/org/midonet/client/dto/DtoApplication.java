/*
 * Copyright 2015 Midokura SARL
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
import javax.ws.rs.core.UriBuilder;
import javax.xml.bind.annotation.XmlRootElement;

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
    private URI ipAddrGroups;
    private URI routers;
    private URI traces;
    private URI writeVersion;
    private URI systemState;
    private URI tenants;
    private URI hostVersions;
    private URI healthMonitors;
    private URI loadBalancers;
    private URI pools;
    private URI poolMembers;
    private URI ports;
    private URI vips;
    private URI vteps;
    private URI licenses;
    private URI licenseStatus;
    private URI installLicense;
    private String adRouteTemplate;
    private String bgpTemplate;
    private String bridgeTemplate;
    private String vlanBridgeTemplate;
    private String chainTemplate;
    private String ruleTemplate;
    private String hostTemplate;
    private String portTemplate;
    private String portGroupTemplate;
    private String ipAddrGroupTemplate;
    private String routeTemplate;
    private String routerTemplate;
    private String tunnelZoneTemplate;
    private String traceTemplate;
    private String tenantTemplate;
    private String healthMonitorTemplate;
    private String loadBalancerTemplate;
    private String poolTemplate;
    private String poolMemberTemplate;
    private String vipTemplate;
    private String vtepTemplate;
    private String licenseTemplate;

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

    public URI getIpAddrGroups() {
        return ipAddrGroups;
    }

    public void setIpAddrGroups(URI ipAddrGroups) {
        this.ipAddrGroups = ipAddrGroups;
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

    public URI getHostVersions() {
        return this.hostVersions;
    }

    public void setHostVersions(URI hostVersions) {
        this.hostVersions = hostVersions;
    }

    public URI getHealthMonitors() {
        return this.healthMonitors;
    }

    public URI getHealthMonitor(UUID id) {
        return UriBuilder.fromPath(getHealthMonitorTemplate()).build(id);
    }

    public void setHealthMonitors(URI healthMonitors) {
        this.healthMonitors = healthMonitors;
    }

    public URI getLoadBalancers() { return this.loadBalancers; }

    public URI getLoadBalancer(UUID id) {
        return UriBuilder.fromPath(getLoadBalancerTemplate()).build(id);
    }

    public void setLoadBalancers(URI loadBalancers) { this.loadBalancers = loadBalancers; }

    public URI getPools() {
        return this.pools;
    }

    public URI getPool(UUID id) {
        return UriBuilder.fromPath(getPoolTemplate()).build(id);
    }

    public void setPools(URI pools) {
        this.pools = pools;
    }

    public URI getPoolMembers() {
        return this.poolMembers;
    }

    public URI getPoolMember(UUID id) {
        return UriBuilder.fromPath(getPoolTemplate()).build(id);
    }

    public void setPoolMembers(URI poolMembers) {
        this.poolMembers = poolMembers;
    }

    public URI getPorts() {
        return this.ports;
    }

    public void setPorts(URI ports) {
        this.ports = ports;
    }

    public URI getVips() {
        return this.vips;
    }

    public URI getVip(UUID id) {
        return UriBuilder.fromPath(getVipTemplate()).build(id);
    }

    public void setVips(URI vips) {
        this.vips = vips;
    }

    public URI getVteps() {
        return this.vteps;
    }

    public void setVteps(URI vteps) {
        this.vteps = vteps;
    }

    public URI getVtep(UUID id) {
        return UriBuilder.fromPath(getVtepTemplate()).build(id);
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

    public String getIpAddrGroupTemplate() {
        return ipAddrGroupTemplate;
    }

    public void setIpAddrGroupTemplate(String ipAddrGroupTemplate) {
        this.ipAddrGroupTemplate = ipAddrGroupTemplate;
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

    public String getHealthMonitorTemplate() {
        return healthMonitorTemplate;
    }

    public void setHealthMonitorTemplate(String healthMonitorTemplate) {
        this.healthMonitorTemplate = healthMonitorTemplate;
    }

    public String getLoadBalancerTemplate() { return loadBalancerTemplate; }

    public void setLoadBalancerTemplate(String loadBalancerTemplate) {
        this.loadBalancerTemplate = loadBalancerTemplate;
    }

    public String getPoolTemplate() {
        return poolTemplate;
    }

    public void setPoolTemplate(String poolTemplate) {
        this.poolTemplate = poolTemplate;
    }

    public String getPoolMemberTemplate() {
        return poolMemberTemplate;
    }

    public void setPoolMemberTemplate(String poolMemberTemplate) {
        this.poolMemberTemplate = poolMemberTemplate;
    }

    public String getVipTemplate() {
        return vipTemplate;
    }

    public void setVipTemplate(String vipTemplate) {
        this.vipTemplate = vipTemplate;
    }

    public String getVtepTemplate() {
        return vtepTemplate;
    }

    public void setVtepTemplate(String vtepTemplate) {
        this.vtepTemplate = vtepTemplate;
    }

    public URI getLicenses() {
        return licenses;
    }

    public void setLicenses(URI licenses) {
        this.licenses = licenses;
    }

    public URI getLicenseStatus() {
        return licenseStatus;
    }

    public void setLicenseStatus(URI licenseStatus) {
        this.licenseStatus = licenseStatus;
    }

    public URI getInstallLicense() {
        return installLicense;
    }

    public void setInstallLicense(URI installLicense) {
        this.installLicense = installLicense;
    }

    public String getLicenseTemplate() {
        return licenseTemplate;
    }

    public void setLicenseTemplate(String licenseTemplate) {
        this.licenseTemplate = licenseTemplate;
    }
}
