/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.rest_api;

import java.util.HashMap;
import java.util.Map;
import javax.ws.rs.core.Response;

import com.midokura.midonet.client.dto.*;
import static com.midokura.midolman.mgmt.VendorMediaType.APPLICATION_BRIDGE_JSON;
import static com.midokura.midolman.mgmt.VendorMediaType.APPLICATION_CHAIN_JSON;
import static com.midokura.midolman.mgmt.VendorMediaType.APPLICATION_JSON;
import static com.midokura.midolman.mgmt.VendorMediaType.APPLICATION_PORTGROUP_JSON;
import static com.midokura.midolman.mgmt.VendorMediaType.APPLICATION_PORT_JSON;
import static com.midokura.midolman.mgmt.VendorMediaType.APPLICATION_ROUTER_JSON;

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
        private final Map<String, DtoRouter> routers;
        private final Map<String, DtoBridge> bridges;
        private final Map<String, DtoRuleChain> chains;
        private final Map<String, DtoMaterializedRouterPort> matRouterPorts;
        private final Map<String, DtoLogicalRouterPort> logRouterPorts;
        private final Map<String, DtoBridgePort> matBridgePorts;
        private final Map<String, DtoLogicalBridgePort> logBridgePorts;
        private final Map<String, DtoPortGroup> portGroups;

        private final Map<String, String> tagToInChains;
        private final Map<String, String> tagToOutChains;
        private final Map<String, String> tagToRouters;
        private final Map<String, String> tagToBridges;
        private final Map<String, String> links;

        public Builder(DtoWebResource resource) {
            this.resource = resource;
            this.routers = new HashMap<String, DtoRouter>();
            this.bridges = new HashMap<String, DtoBridge>();
            this.chains = new HashMap<String, DtoRuleChain>();
            this.matRouterPorts = new HashMap<String, DtoMaterializedRouterPort>();
            this.logRouterPorts = new HashMap<String, DtoLogicalRouterPort>();
            this.matBridgePorts = new HashMap<String, DtoBridgePort>();
            this.logBridgePorts = new HashMap<String, DtoLogicalBridgePort>();
            this.portGroups = new HashMap<String, DtoPortGroup>();

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
                DtoMaterializedRouterPort obj) {
            this.matRouterPorts.put(tag, obj);
            this.tagToRouters.put(tag, routerTag);
            return this;
        }

        public Builder create(String routerTag, String tag,
                DtoLogicalRouterPort obj) {
            this.logRouterPorts.put(tag, obj);
            this.tagToRouters.put(tag, routerTag);
            return this;
        }

        public Builder create(String bridgeTag, String tag, DtoBridgePort obj) {
            this.matBridgePorts.put(tag, obj);
            this.tagToBridges.put(tag, bridgeTag);
            return this;
        }

        public Builder create(String bridgeTag, String tag,
                DtoLogicalBridgePort obj) {
            this.logBridgePorts.put(tag, obj);
            this.tagToBridges.put(tag, bridgeTag);
            return this;
        }

        public Builder create(String tag, DtoPortGroup obj) {
            this.portGroups.put(tag, obj);
            return this;
        }

        public Builder link(String portTag1, String portTag2) {

            if (!this.logRouterPorts.containsKey(portTag1)
                    && !this.logBridgePorts.containsKey(portTag1)) {
                throw new IllegalArgumentException(
                        "portTag1 is not a valid logical port");
            }

            if (!this.logRouterPorts.containsKey(portTag2)
                    && !this.logBridgePorts.containsKey(portTag2)) {
                throw new IllegalArgumentException(
                        "portTag2 is not a valid logical port");
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

        private DtoLogicalPort findLogicalPort(String tag) {
            if (logRouterPorts.containsKey(tag)) {
                return logRouterPorts.get(tag);
            } else {
                return logBridgePorts.get(tag);
            }
        }

        private DtoPort findPort(String tag) {
            if (logRouterPorts.containsKey(tag)) {
                return logRouterPorts.get(tag);
            } else if (logBridgePorts.containsKey(tag)) {
                return logBridgePorts.get(tag);
            } else if (matRouterPorts.containsKey(tag)) {
                return matRouterPorts.get(tag);
            } else {
                return matBridgePorts.get(tag);
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

            for (Map.Entry<String, DtoPortGroup> entry : portGroups.entrySet()) {

                DtoPortGroup obj = entry.getValue();

                obj = resource.postAndVerifyCreated(app.getPortGroups(),
                        APPLICATION_PORTGROUP_JSON, obj, DtoPortGroup.class);
                entry.setValue(obj);
            }

            for (Map.Entry<String, DtoMaterializedRouterPort> entry :
                    matRouterPorts.entrySet()) {

                DtoMaterializedRouterPort obj = entry.getValue();

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
                        APPLICATION_PORT_JSON, entry.getValue(),
                        DtoMaterializedRouterPort.class);
                entry.setValue(obj);
            }

            for (Map.Entry<String, DtoBridgePort> entry : matBridgePorts
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
                        APPLICATION_PORT_JSON, entry.getValue(),
                        DtoBridgePort.class);
                entry.setValue(obj);
            }

            for (Map.Entry<String, DtoLogicalRouterPort> entry : logRouterPorts
                    .entrySet()) {

                DtoLogicalRouterPort obj = entry.getValue();

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
                        APPLICATION_PORT_JSON, entry.getValue(),
                        DtoLogicalRouterPort.class);
                entry.setValue(obj);
            }

            for (Map.Entry<String, DtoLogicalBridgePort> entry : logBridgePorts
                    .entrySet()) {

                DtoLogicalBridgePort obj = entry.getValue();

                // Set the router ID
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
                        APPLICATION_PORT_JSON, entry.getValue(),
                        DtoLogicalBridgePort.class);
                entry.setValue(obj);
            }

            for (Map.Entry<String, String> entry : links.entrySet()) {
                // Get the logical ports
                DtoLogicalPort port1 = findLogicalPort(entry.getKey());
                DtoPort port2 = findPort(entry.getValue());

                resource.postAndVerifyStatus(port1.getLink(),
                        APPLICATION_PORT_JSON,
                        "{\"peerId\": \"" + port2.getId() + "\"}",
                        Response.Status.NO_CONTENT.getStatusCode());
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

    public DtoMaterializedRouterPort getMatRouterPort(String tag) {
        return this.builder.matRouterPorts.get(tag);
    }

    public DtoBridgePort getMatBridgePort(String tag) {
        return this.builder.matBridgePorts.get(tag);
    }

    public DtoLogicalRouterPort getLogRouterPort(String tag) {
        return this.builder.logRouterPorts.get(tag);
    }

    public DtoLogicalBridgePort getLogBridgePort(String tag) {
        return this.builder.logBridgePorts.get(tag);
    }
}
