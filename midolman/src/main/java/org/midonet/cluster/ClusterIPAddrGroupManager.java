/*
 * Copyright 2014 Midokura SARL
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
        public void onSuccess(Set<String> addrStrings) {
            Set<IPAddr> ipAddrs = new HashSet<IPAddr>();
            for (String strAddr : addrStrings) {
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
