/*
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.api.host.rest_api;

import org.midonet.api.rest_api.DtoWebResource;
import org.midonet.client.dto.DtoApplication;
import org.midonet.client.dto.DtoGreTunnelZone;
import org.midonet.client.dto.DtoHost;
import org.midonet.client.dto.DtoHostInterfacePort;
import org.midonet.midolman.host.state.HostDirectory;
import org.midonet.midolman.host.state.HostZkManager;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.StateAccessException;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.midonet.client.VendorMediaType.APPLICATION_JSON_V5;
import static org.midonet.client.VendorMediaType.APPLICATION_TUNNEL_ZONE_JSON;
import static org.midonet.client.VendorMediaType.APPLICATION_HOST_COLLECTION_JSON;
import static org.midonet.client.VendorMediaType.APPLICATION_HOST_INTERFACE_PORT_JSON;

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
public class HostTopology {

    private final Builder builder;

    public static class Builder {

        private final DtoWebResource resource;
        private final HostZkManager hostZkManager;

        private DtoApplication app;
        private final Map<UUID, DtoHost> hosts;
        private final Map<String, DtoGreTunnelZone> greTunnelZones;
        private final Map<UUID, DtoHostInterfacePort> hostInterfacePorts;

        private final Map<UUID, UUID> tagToHosts;

        public Builder(DtoWebResource resource, HostZkManager hostZkManager) {
            this.resource = resource;
            this.hostZkManager = hostZkManager;
            this.hosts = new HashMap<UUID, DtoHost>();
            this.greTunnelZones = new HashMap<String, DtoGreTunnelZone>();
            this.hostInterfacePorts = new HashMap<UUID, DtoHostInterfacePort>();

            this.tagToHosts = new HashMap<UUID, UUID>();
        }

        public DtoWebResource getResource() {
            return this.resource;
        }

        public Builder create(UUID id, DtoHost obj) {
            this.hosts.put(id, obj);
            return this;
        }

        public Builder create(String tag, DtoGreTunnelZone obj) {
            this.greTunnelZones.put(tag, obj);
            return this;
        }

        /**
         * Create the mock topology of the host-interface-port binding.
         *
         * @param hostTag an UUID of the host which the port-interface-port
         *                binding is associated
         * @param tag     an UUID of the port-interface-port.
         * @param obj     a client-side DTO of the port-host-interface.
         * @return        this builder object
         */
        public Builder create(UUID hostTag, UUID tag,
                              DtoHostInterfacePort obj) {
            this.hostInterfacePorts.put(tag, obj);
            this.tagToHosts.put(tag, hostTag);
            return this;
        }

        public HostTopology build()
                throws StateAccessException, SerializationException {


            this.app = resource.getWebResource().path("/")
                    .type(APPLICATION_JSON_V5).get(DtoApplication.class);

            if (hosts.size() > 0) {
                for (Map.Entry<UUID, DtoHost> entry : hosts.entrySet()) {
                    DtoHost obj = entry.getValue();
                    HostDirectory.Metadata metadata
                            = new HostDirectory.Metadata();
                    metadata.setName(obj.getName());
                    hostZkManager.createHost(entry.getKey(), metadata);
                }

                // Get DtoHosts
                URI hostsUri = this.app.getHosts();
                DtoHost[] hostList = resource.getAndVerifyOk(hostsUri,
                        APPLICATION_HOST_COLLECTION_JSON,
                        DtoHost[].class);
                Map<UUID, DtoHost> hostMap = new HashMap<UUID, DtoHost>();
                for (DtoHost host : hostList) {
                    hostMap.put(host.getId(), host);
                }

                for (Map.Entry<UUID, DtoHost> entry : hosts.entrySet()) {
                    entry.setValue(hostMap.get(entry.getKey()));
                }

                // Initialize the topology of the host-interface-port bindings.
                for (Map.Entry<UUID, DtoHostInterfacePort> entry
                        : hostInterfacePorts.entrySet()) {
                    DtoHostInterfacePort hostInterfacePort = entry.getValue();
                    // Set the interface name.
                    UUID tag = tagToHosts.get(entry.getKey());
                    DtoHost host = hosts.get(tag);
                    hostInterfacePort.setInterfaceName(host.getName());
                    // Set the host ID.
                    hostInterfacePort.setHostId(host.getId());
                    hostInterfacePort = resource.postAndVerifyCreated(host.getPorts(),
                            APPLICATION_HOST_INTERFACE_PORT_JSON, hostInterfacePort,
                            DtoHostInterfacePort.class);
                    entry.setValue(hostInterfacePort);
                }
            }

            for (Map.Entry<String, DtoGreTunnelZone> entry
                    : greTunnelZones.entrySet()) {

                DtoGreTunnelZone obj = entry.getValue();

                obj = resource.postAndVerifyCreated(app.getTunnelZones(),
                        APPLICATION_TUNNEL_ZONE_JSON, obj,
                        DtoGreTunnelZone.class);
                entry.setValue(obj);
            }

            return new HostTopology(this);
        }
    }

    private HostTopology(Builder builder) {
        this.builder = builder;
    }

    public DtoApplication getApplication() {
        return this.builder.app;
    }

    public DtoHost getHost(UUID id) {
        return this.builder.hosts.get(id);
    }

    public DtoGreTunnelZone getGreTunnelZone(String tag) {
        return this.builder.greTunnelZones.get(tag);
    }

    /**
     * Get a client-side DTO object associated with the specified UUID tag.
     *
     * @param tag  an UUID of the host-interface-port binding to be got
     * @return     the DTO object of the host-interface-port binding
     */
    public DtoHostInterfacePort getHostInterfacePort(UUID tag) {
        return this.builder.hostInterfacePorts.get(tag);
    }
}
