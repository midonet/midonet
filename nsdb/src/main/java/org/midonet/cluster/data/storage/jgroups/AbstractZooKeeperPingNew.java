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
package org.midonet.cluster.data.storage.jgroups;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.utils.ZKPaths;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.jgroups.Address;
import org.jgroups.Event;
import org.jgroups.PhysicalAddress;
import org.jgroups.View;
import org.jgroups.annotations.Property;
import org.jgroups.protocols.Discovery;
import org.jgroups.protocols.PingData;
import org.jgroups.util.Responses;
import org.jgroups.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * JGroups discovery protocol for discovering group members using an existing
 * zookeeper cluster.
 *
 * Based on JGroups JDBC_PING
 */
public abstract class AbstractZooKeeperPingNew extends Discovery {

    /* -----------------------------------------    Properties     -------------------------------------------------- */

    @Property(description = "If set, a shutdown hook is registered with the JVM to remove the local znode "
            + "from zookeeper. Default is true", writable = false)
    protected boolean register_shutdown_hook = true;

    /* --------------------------------------------- Fields ------------------------------------------------------ */

    private static final Logger midoLog = LoggerFactory.getLogger(AbstractZooKeeperPingNew.class);
    public static final String ROOT_PATH = "/jgroups/registry";
    private volatile String localNodePath = "";

    protected CuratorFramework curator;

    protected abstract CuratorFramework createCurator() throws KeeperException;

    protected CreateMode getCreateMode() throws KeeperException {
        return CreateMode.EPHEMERAL;
    }

    public boolean isDynamic() {return true;}

    public String getDiscoveryPath(String clusterName) {
        return ROOT_PATH + "/" + clusterName;
    }

    public String getLocalNodePath() {
        return getPathForAddress(cluster_name, local_addr);
    }

    public String getPathForAddress(String clusterName, Address address) {
        return getDiscoveryPath(clusterName) + "/" + addressAsString(address);
    }

    @Override
    public void init() throws Exception {
        curator = createCurator();
        super.init();

        if (register_shutdown_hook) {
            Runtime.getRuntime().addShutdownHook(new Thread() {
                public void run() {
                    deleteSelf();
                }
            });
        }
    }

    @Override
    public void stop() {
        deleteSelf();
        super.stop();
    }

    public Object down(Event evt) {
        switch(evt.getType()) {
            case Event.VIEW_CHANGE:
                View old_view=view;
                boolean previous_coord=is_coord;
                Object retval=super.down(evt);
                View new_view=(View)evt.getArg();
                handleView(new_view, old_view, previous_coord != is_coord);
                return retval;
        }
        return super.down(evt);
    }

    @Override
    public void findMembers(final List<Address> members, final boolean initial_discovery, Responses responses) {
        readAll(members, cluster_name, responses);

        PhysicalAddress phys_addr = (PhysicalAddress) down_prot.down(
                new Event(Event.GET_PHYSICAL_ADDRESS, local_addr));
        PingData data = responses.findResponseFrom(local_addr);
        // the logical addr *and* IP address:port have to match
        if(data != null && data.getPhysicalAddr().equals(phys_addr)) {
            if(data.isCoord() && initial_discovery)
                responses.clear();
        }
        else {
            sendDiscoveryResponse(local_addr, phys_addr,
                    UUID.get(local_addr), null, false);
        }

        responses.done();
        writeOwnInformation();
    }

    protected void handleView(View new_view, View old_view, boolean coord_changed) {
        if (coord_changed) {
            writeOwnInformation();
        }
    }

    protected void writeOwnInformation() {
        PhysicalAddress physical_addr = (PhysicalAddress)down(
                new Event(Event.GET_PHYSICAL_ADDRESS, local_addr));
        PingData data = new PingData(local_addr, is_server,
                UUID.get(local_addr), physical_addr).coord(is_coord);
        midoLog.debug("Writing own PingData: {}", data.toString());
        writeDataToNode(data, getLocalNodePath());
    }

    protected void readAll(List<Address> members, String clustername, Responses rsps) {

        try {
            for (String node : curator.getChildren()
                    .forPath(getDiscoveryPath(clustername))) {
                String nodePath = ZKPaths.makePath(getDiscoveryPath(clustername)
                        , node);
                PingData data = readDataFromNode(nodePath);

                midoLog.debug("Discovered broker {} \n at znode: {}",
                        data.toString(), nodePath);

                if(data == null ||
                        (members != null && !members.contains(data.getAddress()))) {
                    //midoLog.debug("Omitted response for unrequested member: {}"
                    // , data.getAddress().toString());
                    continue;
                }

                rsps.addResponse(data, true);
                if(local_addr != null && !local_addr.equals(data.getAddress()))
                    addDiscoveryResponseToCaches(data.getAddress(),
                            data.getLogicalName(), data.getPhysicalAddr());

            }

        } catch (KeeperException.NoNodeException e) {
            midoLog.error("Missing zookeeper node during reading of " +
                    "brokers from zookeeper for discovery", e);
        } catch (Exception e) {
            midoLog.error("Exception during reading of brokers from " +
                    "zookeeper for discovery", e);
        }

    }


    protected void writeDataToNode(PingData data, String path) {
        final byte[] serializedPingData = serializeWithoutView(data);

        try {
            if (curator.checkExists().forPath(path) == null) {
                curator.create().creatingParentsIfNeeded()
                        .withMode(getCreateMode()).forPath(path, serializedPingData);
                midoLog.debug("Created discovery znode at: {}", path);
            } else {
                curator.setData().forPath(path, serializedPingData);
                midoLog.debug("Updated discovery znode at: {}", path);
            }
        } catch (Exception ex) {
            midoLog.error("Error saving ping data", ex);
        }
    }

    protected PingData readDataFromNode(String path) {
        try {
            byte[] bytes = curator.getData().forPath(path);
            return deserialize(bytes);
        } catch (Exception e) {
            midoLog.warn("Failed to read ZooKeeper znode: {}", path, e);
        }

        return null;
    }

    protected void delete(String clustername, Address addressToDelete) {
        removeNode(getPathForAddress(clustername, addressToDelete));
    }

    protected void removeNode(String path) {
        try {
            curator.delete().forPath(path);
        } catch (KeeperException.NoNodeException e) {
            midoLog.error("Node already removed", e);
        } catch (Exception e) {
            midoLog.error("Failed removing znode", e);
        }
    }

    protected void deleteSelf() {
        removeNode(getLocalNodePath());
    }

    private static final boolean stringIsEmpty(final String value) {
        return value == null || value.trim().isEmpty();
    }

}
