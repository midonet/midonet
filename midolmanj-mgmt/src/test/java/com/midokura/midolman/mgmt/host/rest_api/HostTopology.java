/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.host.rest_api;

import com.midokura.midolman.host.state.HostDirectory;
import com.midokura.midolman.host.state.HostZkManager;
import com.midokura.midolman.mgmt.VendorMediaType;
import com.midokura.midolman.mgmt.rest_api.DtoWebResource;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midonet.client.dto.DtoApplication;
import com.midokura.midonet.client.dto.DtoGreTunnelZone;
import com.midokura.midonet.client.dto.DtoHost;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static com.midokura.midolman.mgmt.VendorMediaType.APPLICATION_JSON;
import static com.midokura.midolman.mgmt.VendorMediaType.APPLICATION_TUNNEL_ZONE_JSON;

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

        private final Map<String, String> tagToHosts;

        public Builder(DtoWebResource resource, HostZkManager hostZkManager) {
            this.resource = resource;
            this.hostZkManager = hostZkManager;
            this.hosts = new HashMap<UUID, DtoHost>();
            this.greTunnelZones = new HashMap<String, DtoGreTunnelZone>();

            this.tagToHosts = new HashMap<String, String>();
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

        public HostTopology build() throws StateAccessException {


            this.app = resource.getWebResource().path("/")
                    .type(APPLICATION_JSON).get(DtoApplication.class);

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
                        VendorMediaType.APPLICATION_HOST_COLLECTION_JSON,
                        DtoHost[].class);
                Map<UUID, DtoHost> hostMap = new HashMap<UUID, DtoHost>();
                for (DtoHost host : hostList) {
                    hostMap.put(host.getId(), host);
                }

                for (Map.Entry<UUID, DtoHost> entry : hosts.entrySet()) {
                    entry.setValue(hostMap.get(entry.getKey()));
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
}
