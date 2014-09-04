/*
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.api.host.rest_api;

import org.midonet.api.rest_api.DtoWebResource;
import org.midonet.api.servlet.JerseyGuiceTestServletContextListener;
import org.midonet.client.dto.DtoApplication;
import org.midonet.client.dto.DtoHost;
import org.midonet.client.dto.DtoHostInterfacePort;
import org.midonet.client.dto.DtoTunnelZone;
import org.midonet.client.dto.DtoTunnelZoneHost;
import org.midonet.midolman.host.state.HostDirectory;
import org.midonet.midolman.host.state.HostZkManager;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.StateAccessException;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.midonet.client.VendorMediaType.APPLICATION_JSON_V5;
import static org.midonet.client.VendorMediaType.APPLICATION_TUNNEL_ZONE_HOST_JSON;
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
        private final Map<String, DtoTunnelZone> tunnelZones;
        private final Map<UUID, DtoTunnelZoneHost> tunnelZoneHosts;
        private final Map<UUID, DtoHostInterfacePort> hostInterfacePorts;

        private final Map<UUID, UUID> tagToHosts;
        private final Map<UUID, String> tagToTunnelZone;

        public Builder(DtoWebResource resource) {
            this.resource = resource;
            this.hostZkManager = JerseyGuiceTestServletContextListener
                                 .getHostZkManager();
            this.hosts = new HashMap<>();
            this.tunnelZones = new HashMap<>();
            this.tunnelZoneHosts = new HashMap<>();
            this.hostInterfacePorts = new HashMap<>();

            this.tagToHosts = new HashMap<>();
            this.tagToTunnelZone = new HashMap<>();
        }

        public DtoWebResource getResource() {
            return this.resource;
        }

        /**
         * Create the mock topology of the host.
         *
         * @param tag  The host's UUID.
         * @param host The host's client-side DTO object.
         * @return This builder object.
         */
        public Builder create(UUID tag, DtoHost host) {
            this.hosts.put(tag, host);
            return this;
        }

        /**
         * Create the mock topology of the GRE tunnel zone.
         *
         * @param tag        The name of tunnel zone.
         * @param tunnelZone A client-side DTO object of the tunnel zone.
         * @return This builde object.
         */
        public Builder create(String tag, DtoTunnelZone tunnelZone) {
            this.tunnelZones.put(tag, tunnelZone);
            return this;
        }

        /**
         * Create the mock topology of the host-interface-port binding.
         *
         * @param hostTag           A UUID of the host with which the
         *                          port-interface-port binding is associated
         * @param tag               An UUID of the port-interface-port.
         * @param hostInterfacePort A client-side DTO object of the
         *                          port-host-interface.
         * @return This builder object.
         */
        public Builder create(UUID hostTag, UUID tag,
                              DtoHostInterfacePort hostInterfacePort) {
            this.hostInterfacePorts.put(tag, hostInterfacePort);
            this.tagToHosts.put(tag, hostTag);
            return this;
        }

        /**
         * Create the mock topology of the tunnel zone host.
         *
         * @param tunnelZoneTag  A UUID of the tunnel zone to which the host
         *                       belongs.
         * @param tag            The host's UUID.
         * @param tunnelZoneHost A client-side DTO object of the tunnel zone
         *                       host.
         * @return This builder object.
         */
        public Builder create(String tunnelZoneTag, UUID tag,
                              DtoTunnelZoneHost tunnelZoneHost) {
            this.tunnelZoneHosts.put(tag, tunnelZoneHost);
            this.tagToTunnelZone.put(tag, tunnelZoneTag);
            return this;
        }

        public HostTopology build()
                throws StateAccessException, SerializationException {
            this.app = resource.getWebResource().path("/")
                    .type(APPLICATION_JSON_V5).get(DtoApplication.class);

            for (Map.Entry<String, DtoTunnelZone> entry
                    : tunnelZones.entrySet()) {
                DtoTunnelZone tunnelZone = entry.getValue();
                tunnelZone = resource.postAndVerifyCreated(app.getTunnelZones(),
                        APPLICATION_TUNNEL_ZONE_JSON, tunnelZone,
                        DtoTunnelZone.class);
                entry.setValue(tunnelZone);
            }

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

                for (Map.Entry<UUID, DtoTunnelZoneHost> entry :
                        tunnelZoneHosts.entrySet()) {
                    DtoTunnelZoneHost tunnelZoneHost = entry.getValue();
                    // Set the tunnel zone ID.
                    String tunnelZoneTag = tagToTunnelZone.get(entry.getKey());
                    DtoTunnelZone tunnelZone =
                            tunnelZones.get(tunnelZoneTag);
                    tunnelZoneHost.setTunnelZoneId(tunnelZone.getId());
                    tunnelZoneHost = resource.postAndVerifyCreated(
                            tunnelZone.getHosts(),
                            APPLICATION_TUNNEL_ZONE_HOST_JSON,
                            tunnelZoneHost,
                            DtoTunnelZoneHost.class);
                    entry.setValue(tunnelZoneHost);
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
                    hostInterfacePort = resource.postAndVerifyCreated(
                            host.getPorts(),
                            APPLICATION_HOST_INTERFACE_PORT_JSON,
                            hostInterfacePort,
                            DtoHostInterfacePort.class);
                    entry.setValue(hostInterfacePort);
                }
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

    public DtoTunnelZone getGreTunnelZone(String tag) {
        return this.builder.tunnelZones.get(tag);
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
