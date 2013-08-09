/*
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.api.rest_api;

import java.util.HashMap;
import java.util.Map;
import javax.ws.rs.core.Response;

import org.midonet.client.dto.*;
import static org.midonet.api.VendorMediaType.APPLICATION_BRIDGE_JSON;
import static org.midonet.api.VendorMediaType.APPLICATION_CHAIN_JSON;
import static org.midonet.api.VendorMediaType.APPLICATION_CONDITION_JSON;
import static org.midonet.api.VendorMediaType.APPLICATION_JSON;
import static org.midonet.api.VendorMediaType.APPLICATION_PORTGROUP_JSON;
import static org.midonet.api.VendorMediaType.APPLICATION_PORT_LINK_JSON;
import static org.midonet.api.VendorMediaType.APPLICATION_PORT_V2_JSON;
import static org.midonet.api.VendorMediaType.APPLICATION_ROUTER_JSON;
import static org.midonet.api.VendorMediaType.APPLICATION_TENANT_COLLECTION_JSON;


/**
 * Class to assist creating a network topology in unit tests. An example usage:
 *
 * <pre>
 * {@code
 *    Topology t;
 *
 *    @Before
 *    void setup() {
 *      t = new Topology.builder()
 *            .create("router1", router1)
 *            .create("router1", "port1", port11)   // Tag each object
 *            .build();  // This actually creates the objects in the server,
 *                       // and verifies that the POST operations succeeded.
 *     }
 *
 *    @Test
 *    void testPortCreate() {
 *       // Get the tagged object
 *       DtoRouter router1 = t.getRouter("router1");
 *       // Run this test in the setup created.
 *    }
 *  }
 * </pre>
 */
public class Topology {
    private final Builder builder;

    public static class Builder {

        private final DtoWebResource resource;

        private DtoApplication app;
        private final Map<String, DtoTenant> tenants;
        private final Map<String, DtoRouter> routers;
        private final Map<String, DtoBridge> bridges;
        private final Map<String, DtoRuleChain> chains;
        private final Map<String, DtoRouterPort> routerPorts;
        private final Map<String, DtoBridgePort> bridgePorts;
        private final Map<String, DtoPortGroup> portGroups;
        private final Map<String, DtoTraceCondition> traceConditions;

        private final Map<String, String> tagToInChains;
        private final Map<String, String> tagToOutChains;
        private final Map<String, String> tagToRouters;
        private final Map<String, String> tagToBridges;
        private final Map<String, String> links;

        public Builder(DtoWebResource resource) {
            this.resource = resource;
            this.tenants = new HashMap<String, DtoTenant>();
            this.routers = new HashMap<String, DtoRouter>();
            this.bridges = new HashMap<String, DtoBridge>();
            this.chains = new HashMap<String, DtoRuleChain>();
            this.routerPorts = new HashMap<String, DtoRouterPort>();
            this.bridgePorts = new HashMap<String, DtoBridgePort>();
            this.portGroups = new HashMap<String, DtoPortGroup>();
            this.traceConditions = new HashMap<String, DtoTraceCondition>();

            this.links = new HashMap<String, String>();
            this.tagToInChains = new HashMap<String, String>();
            this.tagToOutChains = new HashMap<String, String>();
            this.tagToRouters = new HashMap<String, String>();
            this.tagToBridges = new HashMap<String, String>();
        }

        public DtoWebResource getResource() {
            return this.resource;
        }

        public Builder create(String tag, DtoRouter obj) {
            this.routers.put(tag, obj);
            return this;
        }

        public Builder create(String tag, DtoBridge obj) {
            this.bridges.put(tag, obj);
            return this;
        }

        public Builder create(String tag, DtoRuleChain obj) {
            this.chains.put(tag, obj);
            return this;
        }

        public Builder create(String routerTag, String tag,
                              DtoRouterPort obj) {
            this.routerPorts.put(tag, obj);
            this.tagToRouters.put(tag, routerTag);
            return this;
        }

        public Builder create(String bridgeTag, String tag,
                              DtoBridgePort obj) {
            this.bridgePorts.put(tag, obj);
            this.tagToBridges.put(tag, bridgeTag);
            return this;
        }

        public Builder create(String tag, DtoPortGroup obj) {
            this.portGroups.put(tag, obj);
            return this;
        }

        public Builder create(String tag, DtoTraceCondition traceCondition) {
            this.traceConditions.put(tag, traceCondition);
            return this;
        }

        public Builder link(String portTag1, String portTag2) {

            if (!this.routerPorts.containsKey(portTag1)
                && !this.bridgePorts.containsKey(portTag1)) {
                throw new IllegalArgumentException(
                    "portTag1 is not a valid port");
            }

            if (!this.routerPorts.containsKey(portTag2)
                && !this.bridgePorts.containsKey(portTag2)) {
                throw new IllegalArgumentException(
                    "portTag2 is not a valid port");
            }

            this.links.put(portTag1, portTag2);
            return this;
        }

        public Builder applyInChain(String tag, String chainTag) {
            this.tagToInChains.put(tag, chainTag);
            return this;
        }

        public Builder applyOutChain(String tag, String chainTag) {
            this.tagToOutChains.put(tag, chainTag);
            return this;
        }

        private DtoPort findPort(String tag) {
            if (bridgePorts.containsKey(tag)) {
                return bridgePorts.get(tag);
            } else {
                return routerPorts.get(tag);
            }
        }

        public Topology build() {

            this.app = resource.getWebResource().path("/")
                .type(APPLICATION_JSON).get(DtoApplication.class);

            for (Map.Entry<String, DtoRuleChain> entry : chains.entrySet()) {
                DtoRuleChain obj = entry.getValue();
                obj = resource.postAndVerifyCreated(app.getChains(),
                    APPLICATION_CHAIN_JSON, obj, DtoRuleChain.class);
                entry.setValue(obj);
            }

            for (Map.Entry<String, DtoRouter> entry : routers.entrySet()) {

                DtoRouter obj = entry.getValue();

                // Set the inbound chain ID
                String tag = tagToInChains.get(entry.getKey());
                if (tag != null) {
                    DtoRuleChain c = chains.get(tag);
                    obj.setInboundFilterId(c.getId());
                }

                // Set the outbound chain ID
                tag = tagToInChains.get(entry.getKey());
                if (tag != null) {
                    DtoRuleChain c = chains.get(tag);
                    obj.setOutboundFilterId(c.getId());
                }

                obj = resource.postAndVerifyCreated(app.getRouters(),
                    APPLICATION_ROUTER_JSON, obj, DtoRouter.class);
                entry.setValue(obj);
            }

            for (Map.Entry<String, DtoBridge> entry : bridges.entrySet()) {

                DtoBridge obj = entry.getValue();

                // Set the inbound chain ID
                String tag = tagToInChains.get(entry.getKey());
                if (tag != null) {
                    DtoRuleChain c = chains.get(tag);
                    obj.setInboundFilterId(c.getId());
                }

                // Set the outbound chain ID
                tag = tagToInChains.get(entry.getKey());
                if (tag != null) {
                    DtoRuleChain c = chains.get(tag);
                    obj.setOutboundFilterId(c.getId());
                }
                obj = resource.postAndVerifyCreated(app.getBridges(),
                    APPLICATION_BRIDGE_JSON, obj, DtoBridge.class);
                entry.setValue(obj);
            }

            for (Map.Entry<String, DtoPortGroup> entry
                : portGroups.entrySet()) {

                DtoPortGroup obj = entry.getValue();

                obj = resource.postAndVerifyCreated(app.getPortGroups(),
                    APPLICATION_PORTGROUP_JSON, obj, DtoPortGroup.class);
                entry.setValue(obj);
            }

            for (Map.Entry<String, DtoRouterPort> entry :
                routerPorts.entrySet()) {

                DtoRouterPort obj = entry.getValue();

                // Set the router ID
                String tag = tagToRouters.get(entry.getKey());
                DtoRouter r = routers.get(tag);
                obj.setDeviceId(r.getId());

                // Set the inbound chain ID
                tag = tagToInChains.get(entry.getKey());
                if (tag != null) {
                    DtoRuleChain c = chains.get(tag);
                    obj.setInboundFilterId(c.getId());
                }

                // Set the outbound chain ID
                tag = tagToInChains.get(entry.getKey());
                if (tag != null) {
                    DtoRuleChain c = chains.get(tag);
                    obj.setOutboundFilterId(c.getId());
                }

                obj = resource.postAndVerifyCreated(r.getPorts(),
                    APPLICATION_PORT_V2_JSON, entry.getValue(),
                    DtoRouterPort.class);
                entry.setValue(obj);
            }

            for (Map.Entry<String, DtoBridgePort> entry : bridgePorts
                .entrySet()) {

                DtoBridgePort obj = entry.getValue();

                // Set the bridge ID
                String tag = tagToBridges.get(entry.getKey());
                DtoBridge b = bridges.get(tag);
                obj.setDeviceId(b.getId());

                // Set the inbound chain ID
                tag = tagToInChains.get(entry.getKey());
                if (tag != null) {
                    DtoRuleChain c = chains.get(tag);
                    obj.setInboundFilterId(c.getId());
                }

                // Set the outbound chain ID
                tag = tagToInChains.get(entry.getKey());
                if (tag != null) {
                    DtoRuleChain c = chains.get(tag);
                    obj.setOutboundFilterId(c.getId());
                }

                obj = resource.postAndVerifyCreated(b.getPorts(),
                    APPLICATION_PORT_V2_JSON, entry.getValue(),
                    DtoBridgePort.class);
                entry.setValue(obj);
            }

            for (Map.Entry<String, String> entry : links.entrySet()) {
                // Get the Interior ports
                DtoPort port1 = findPort(entry.getKey());
                DtoPort port2 = findPort(entry.getValue());

                DtoLink link = new DtoLink();
                link.setPeerId(port2.getId());
                resource.postAndVerifyStatus(port1.getLink(),
                    APPLICATION_PORT_LINK_JSON, link,
                    Response.Status.CREATED.getStatusCode());
            }

            for (Map.Entry<String, DtoTraceCondition> entry :
                traceConditions.entrySet()) {
                DtoTraceCondition traceCondition = entry.getValue();
                traceCondition =
                    resource.postAndVerifyCreated(app.getTraceConditions(),
                        APPLICATION_CONDITION_JSON, traceCondition,
                        DtoTraceCondition.class);
                entry.setValue(traceCondition);
            }

            // Tenants are created behind the scene.  Get all tenants
            DtoTenant[] tenantList = resource.getAndVerifyOk(app.getTenants(),
                APPLICATION_TENANT_COLLECTION_JSON, DtoTenant[].class);
            if (tenantList != null) {
                for (DtoTenant t : tenantList) {
                    tenants.put(t.getId(), t);
                }
            }

            return new Topology(this);
        }
    }

    private Topology(Builder builder) {
        this.builder = builder;
    }

    public DtoApplication getApplication() {
        return this.builder.app;
    }

    public DtoTenant getTenant(String id) {
        return this.builder.tenants.get(id);
    }

    public DtoRouter getRouter(String tag) {
        return this.builder.routers.get(tag);
    }

    public DtoBridge getBridge(String tag) {
        return this.builder.bridges.get(tag);
    }

    public DtoPortGroup getPortGroup(String tag) {
        return this.builder.portGroups.get(tag);
    }

    public DtoRuleChain getChain(String tag) {
        return this.builder.chains.get(tag);
    }

    public DtoRouterPort getRouterPort(String tag) {
        return this.builder.routerPorts.get(tag);
    }

    public DtoBridgePort getBridgePort(String tag) {
        return this.builder.bridgePorts.get(tag);
    }

    public DtoTraceCondition getTraceCondition(String tag) {
        return this.builder.traceConditions.get(tag);
    }
}
