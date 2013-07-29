/*
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.api.rest_api;

import org.midonet.client.dto.*;

import javax.ws.rs.core.Response;
import java.util.HashMap;
import java.util.Map;

import static org.midonet.api.VendorMediaType.*;


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
        private final Map<String, DtoVlanBridge> vlanBridges;
        private final Map<String, DtoRuleChain> chains;
        private final Map<String, DtoExteriorRouterPort> extRouterPorts;
        private final Map<String, DtoInteriorRouterPort> intRouterPorts;
        private final Map<String, DtoVlanBridgeTrunkPort> trunkVlanBridgePorts;
        private final Map<String, DtoVlanBridgeInteriorPort> interiorVlanBridgePorts;
        private final Map<String, DtoBridgePort> extBridgePorts;
        private final Map<String, DtoInteriorBridgePort> intBridgePorts;
        private final Map<String, DtoPortGroup> portGroups;

        private final Map<String, String> tagToInChains;
        private final Map<String, String> tagToOutChains;
        private final Map<String, String> tagToRouters;
        private final Map<String, String> tagToBridges;
        private final Map<String, String> tagToVlanBridges;
        private final Map<String, String> links;

        public Builder(DtoWebResource resource) {
            this.resource = resource;
            this.tenants = new HashMap<String, DtoTenant>();
            this.routers = new HashMap<String, DtoRouter>();
            this.bridges = new HashMap<String, DtoBridge>();
            this.vlanBridges = new HashMap<String, DtoVlanBridge>();
            this.chains = new HashMap<String, DtoRuleChain>();
            this.extRouterPorts = new HashMap<String, DtoExteriorRouterPort>();
            this.intRouterPorts = new HashMap<String, DtoInteriorRouterPort>();
            this.trunkVlanBridgePorts = new HashMap<String, DtoVlanBridgeTrunkPort>();
            this.interiorVlanBridgePorts = new HashMap<String, DtoVlanBridgeInteriorPort>();
            this.extBridgePorts = new HashMap<String, DtoBridgePort>();
            this.intBridgePorts = new HashMap<String, DtoInteriorBridgePort>();
            this.portGroups = new HashMap<String, DtoPortGroup>();

            this.links = new HashMap<String, String>();
            this.tagToInChains = new HashMap<String, String>();
            this.tagToOutChains = new HashMap<String, String>();
            this.tagToRouters = new HashMap<String, String>();
            this.tagToBridges = new HashMap<String, String>();
            this.tagToVlanBridges = new HashMap<String, String>();
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

        public Builder create(String tag, DtoVlanBridge obj) {
            this.vlanBridges.put(tag, obj);
            return this;
        }

        public Builder create(String tag, DtoRuleChain obj) {
            this.chains.put(tag, obj);
            return this;
        }

        public Builder create(String routerTag, String tag,
                DtoExteriorRouterPort obj) {
            this.extRouterPorts.put(tag, obj);
            this.tagToRouters.put(tag, routerTag);
            return this;
        }

        public Builder create(String routerTag, String tag,
                DtoInteriorRouterPort obj) {
            this.intRouterPorts.put(tag, obj);
            this.tagToRouters.put(tag, routerTag);
            return this;
        }

        public Builder create(String vlanBridgeTag, String tag,
                              DtoVlanBridgeInteriorPort obj) {
            this.interiorVlanBridgePorts.put(tag, obj);
            this.tagToVlanBridges.put(tag, vlanBridgeTag);
            return this;
        }

        public Builder create(String vlanBridgeTag, String tag,
                              DtoVlanBridgeTrunkPort obj) {
            this.trunkVlanBridgePorts.put(tag, obj);
            this.tagToVlanBridges.put(tag, vlanBridgeTag);
            return this;
        }

        public Builder create(String bridgeTag, String tag, DtoBridgePort obj) {
            this.extBridgePorts.put(tag, obj);
            this.tagToBridges.put(tag, bridgeTag);
            return this;
        }

        public Builder create(String bridgeTag, String tag,
                DtoInteriorBridgePort obj) {
            this.intBridgePorts.put(tag, obj);
            this.tagToBridges.put(tag, bridgeTag);
            return this;
        }

        public Builder create(String tag, DtoPortGroup obj) {
            this.portGroups.put(tag, obj);
            return this;
        }

        public Builder link(String portTag1, String portTag2) {

            if (!this.intRouterPorts.containsKey(portTag1)
                    && !this.intBridgePorts.containsKey(portTag1)) {
                throw new IllegalArgumentException(
                        "portTag1 is not a valid interior port");
            }

            if (!this.extRouterPorts.containsKey(portTag2)
                    && !this.extBridgePorts.containsKey(portTag2)) {
                throw new IllegalArgumentException(
                        "portTag2 is not a valid interior port");
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

        private DtoInteriorPort findInteriorPort(String tag) {
            if (intRouterPorts.containsKey(tag)) {
                return intRouterPorts.get(tag);
            } else {
                return intBridgePorts.get(tag);
            }
        }

        private DtoPort findPort(String tag) {
            if (intRouterPorts.containsKey(tag)) {
                return intRouterPorts.get(tag);
            } else if (intBridgePorts.containsKey(tag)) {
                return intBridgePorts.get(tag);
            } else if (extRouterPorts.containsKey(tag)) {
                return extRouterPorts.get(tag);
            } else {
                return extBridgePorts.get(tag);
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

            for (Map.Entry<String, DtoVlanBridge> entry : vlanBridges.entrySet()) {

                DtoVlanBridge obj = entry.getValue();
                // no chains in this device
                obj = resource.postAndVerifyCreated(app.getVlanBridges(),
                       APPLICATION_VLAN_BRIDGE_JSON, obj, DtoVlanBridge.class);
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

            for (Map.Entry<String, DtoExteriorRouterPort> entry :
                    extRouterPorts.entrySet()) {

                DtoExteriorRouterPort obj = entry.getValue();

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
                        DtoExteriorRouterPort.class);
                entry.setValue(obj);
            }

            for (Map.Entry<String, DtoBridgePort> entry : extBridgePorts
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

            for (Map.Entry<String, DtoInteriorRouterPort> entry : intRouterPorts
                    .entrySet()) {

                DtoInteriorRouterPort obj = entry.getValue();

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
                        DtoInteriorRouterPort.class);
                entry.setValue(obj);
            }

            for (Map.Entry<String, DtoInteriorBridgePort> entry : intBridgePorts
                    .entrySet()) {

                DtoInteriorBridgePort obj = entry.getValue();

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
                        DtoInteriorBridgePort.class);
                entry.setValue(obj);
            }

            for (Map.Entry<String, DtoVlanBridgeInteriorPort> entry :
                interiorVlanBridgePorts.entrySet()) {

                DtoVlanBridgeInteriorPort obj = entry.getValue();

                // Set the vlan bridge ID
                String tag = tagToVlanBridges.get(entry.getKey());
                DtoVlanBridge b = vlanBridges.get(tag);
                obj.setDeviceId(b.getId());

                // No chains in this device's ports
                obj = resource.postAndVerifyCreated(b.getInteriorPorts(),
                                                    APPLICATION_PORT_JSON, entry.getValue(),
                                                    DtoVlanBridgeInteriorPort.class);

                entry.setValue(obj);
            }

            for (Map.Entry<String, DtoVlanBridgeTrunkPort> entry :
                trunkVlanBridgePorts.entrySet()) {

                DtoVlanBridgeTrunkPort obj = entry.getValue();

                // Set the vlan-bridge ID
                String tag = tagToVlanBridges.get(entry.getKey());
                DtoVlanBridge b = vlanBridges.get(tag);
                obj.setDeviceId(b.getId());

                // No chains in this device's ports
                obj = resource.postAndVerifyCreated(b.getTrunkPorts(),
                                                    APPLICATION_PORT_JSON, entry.getValue(),
                                                    DtoVlanBridgeTrunkPort.class);
                entry.setValue(obj);
            }

            for (Map.Entry<String, String> entry : links.entrySet()) {
                // Get the Interior ports
                DtoInteriorPort port1 = findInteriorPort(entry.getKey());
                DtoPort port2 = findPort(entry.getValue());

                DtoLink link = new DtoLink();
                link.setPeerId(port2.getId());
                resource.postAndVerifyStatus(port1.getLink(),
                        APPLICATION_PORT_LINK_JSON, link,
                        Response.Status.CREATED.getStatusCode());
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

    public DtoVlanBridge getVlanBridge(String tag) {
        return this.builder.vlanBridges.get(tag);
    }

    public DtoPortGroup getPortGroup(String tag) {
        return this.builder.portGroups.get(tag);
    }

    public DtoRuleChain getChain(String tag) {
        return this.builder.chains.get(tag);
    }

    public DtoExteriorRouterPort getExtRouterPort(String tag) {
        return this.builder.extRouterPorts.get(tag);
    }

    public DtoBridgePort getExtBridgePort(String tag) {
        return this.builder.extBridgePorts.get(tag);
    }

    public DtoVlanBridgeInteriorPort getVlanBridgeIntPort(String tag) {
        return this.builder.interiorVlanBridgePorts.get(tag);
    }

    public DtoVlanBridgeTrunkPort getVlanBridgeTrunkPort(String tag) {
        return this.builder.trunkVlanBridgePorts.get(tag);
    }

    public DtoInteriorRouterPort getIntRouterPort(String tag) {
        return this.builder.intRouterPorts.get(tag);
    }

    public DtoInteriorBridgePort getIntBridgePort(String tag) {
        return this.builder.intBridgePorts.get(tag);
    }
}
