/*
 * Copyright (c) 2013 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.cluster;

import com.google.inject.Inject;
import org.midonet.cluster.client.IPAddrGroupBuilder;
import org.midonet.midolman.state.zkManagers.IpAddrGroupZkManager;
import org.midonet.packets.IPAddr;
import org.midonet.packets.IPAddr$;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class ClusterIPAddrGroupManager
        extends ClusterManager<IPAddrGroupBuilder> {
    private static final Logger log = LoggerFactory
            .getLogger(ClusterIPAddrGroupManager.class);

    @Inject
    IpAddrGroupZkManager ipAddrGroupManager;

    @Override
    protected void getConfig(UUID ipAddrGroupId) {
        IPAddrSetCallback callback = new IPAddrSetCallback(ipAddrGroupId);
        ipAddrGroupManager.getAddrsAsync(ipAddrGroupId, callback, callback);
    }

    private class IPAddrSetCallback extends CallbackWithWatcher<Set<String>> {

        private UUID ipAddrGroupId;

        private IPAddrSetCallback(UUID ipAddrGroupId) {
            this.ipAddrGroupId = ipAddrGroupId;
        }

        @Override
        protected String describe() {
            return "IPAddrGroup: " + ipAddrGroupId;
        }

        @Override
        public void onSuccess(Result<Set<String>> data) {
            Set<IPAddr> ipAddrs = new HashSet<IPAddr>();
            for (String strAddr : data.getData()) {
                try {
                    ipAddrs.add(IPAddr$.MODULE$.fromString(strAddr));
                } catch (Exception ex) {
                    log.error("Caught exception parsing IP address {} from " +
                              "IP address group {}'s Zookeeper data. ZK may " +
                              "be corrupt.",
                              new Object[]{strAddr, ipAddrGroupId, ex});
                }
            }
            getBuilder(ipAddrGroupId).setAddrs(ipAddrs);
        }

        @Override
        protected Runnable makeRetry() {
            return new Runnable() {
                @Override
                public void run() {
                    ipAddrGroupManager.getAddrsAsync(ipAddrGroupId,
                            IPAddrSetCallback.this, IPAddrSetCallback.this);
                }
            };
        }

        // TODO: Is this needed? Can the data ever change?
        @Override
        public void pathDataChanged(String path) {
            ipAddrGroupManager.getAddrsAsync(ipAddrGroupId, this, this);
        }

        @Override
        public void pathChildrenUpdated(String path) {
            ipAddrGroupManager.getAddrsAsync(ipAddrGroupId, this, this);
        }
    }
}
