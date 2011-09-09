package com.midokura.midolman.state;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.net.UnknownHostException;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.Vector;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;

import com.midokura.midolman.layer3.Route;
import com.midokura.midolman.rules.NatTarget;
import com.midokura.midolman.rules.Rule;

public class RouterDirectory {

    public static class RouterConfig implements Serializable {

        private static final long serialVersionUID = -7029926675019840294L;

        public RouterConfig() {
        }
        
        public RouterConfig(String name, UUID tenantId) {
            super();
            this.name = name;
            this.tenantId = tenantId;
        }

        public String name;
        public UUID tenantId;
    }

    public static byte[] routerToBytes(RouterConfig tenant) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ObjectOutputStream out = new ObjectOutputStream(bos);
        out.writeObject(tenant);
        out.close();
        return bos.toByteArray();
    }    

    public static RouterConfig bytesToRouter(byte[] data)
            throws IOException, ClassNotFoundException, KeeperException,
                InterruptedException {
        ByteArrayInputStream bis = new ByteArrayInputStream(data);
        ObjectInputStream in = new ObjectInputStream(bis);
        RouterConfig router = (RouterConfig) in.readObject();
        return router;
    }
        
    
    Directory dir;

    public RouterDirectory(Directory dir) {
        this.dir = dir;
    }

    private String getSnatBlocksPath(UUID routerId) {
        StringBuilder strb = new StringBuilder("/");
        strb.append(routerId.toString()).append("/").append("snat_blocks");
        return strb.toString();
    }

    private String getRoutingTablePath(UUID routerId) {
        StringBuilder strb = new StringBuilder("/");
        strb.append(routerId.toString()).append("/").append("routing_table");
        return strb.toString();
    }

    private String getRoutesPath(UUID routerId) {
        StringBuilder strb = new StringBuilder("/");
        strb.append(routerId.toString()).append("/").append("routes");
        return strb.toString();
    }

    private String getPathForRoute(UUID routerId, Route rt) {
        StringBuilder strb = new StringBuilder("/");
        strb.append(routerId.toString()).append("/").append("routes");
        strb.append("/").append(rt.toString());
        return strb.toString();
    }

    private String getRuleChainsPath(UUID routerId) {
        StringBuilder strb = new StringBuilder("/");
        strb.append(routerId.toString()).append("/").append("rule_chains");
        return strb.toString();
    }

    private String getPathForChain(UUID routerId, String chain) {
        StringBuilder strb = new StringBuilder("/");
        strb.append(routerId.toString()).append("/").append("rule_chains");
        strb.append("/").append(chain);
        return strb.toString();
    }

    /**
     * Get a RouterConfig object for the given ID.
     * 
     * @param id  UUID of router to fetch.
     * @return  RouterConfig object with the given ID.
     * @throws IOException  Error serializing.
     * @throws ClassNotFoundException  Class not found.
     * @throws KeeperException  Zookeeper error.
     * @throws InterruptedException  Thread paused too long.
     */
    public RouterConfig getRouter(UUID id) 
            throws IOException, ClassNotFoundException, KeeperException, 
                    InterruptedException {
        byte[] data = dir.get("/" + id.toString(), null);
        RouterConfig routerConfig = bytesToRouter(data);
        return routerConfig;
    }
    
    public void addRouter(UUID routerId, RouterConfig router) 
            throws InterruptedException, KeeperException, IOException {
        byte[] data = routerToBytes(router);
        
        // Use try-catch blocks to avoid getting stuck in a half-created state.
        try {
            dir.add("/" + routerId.toString(), data, CreateMode.PERSISTENT);
        } catch (KeeperException e) {
        	throw e;
        }
        try {
            dir.add(getRoutingTablePath(routerId), null, CreateMode.PERSISTENT);
        } catch (KeeperException e) {
        }
        try {
            dir.add(getRoutesPath(routerId), null, CreateMode.PERSISTENT);
        } catch (KeeperException e) {
        }
        try {
            dir.add(getRuleChainsPath(routerId), null, CreateMode.PERSISTENT);
        } catch (KeeperException e) {
        }
        try {
            dir.add(getSnatBlocksPath(routerId), null, CreateMode.PERSISTENT);
        } catch (KeeperException e) {
        }
    }

    public boolean exists(UUID routerId) throws KeeperException, InterruptedException {
        return dir.has("/" + routerId.toString());
    }

    public void deleteRouter(UUID routerId) throws InterruptedException,
            KeeperException {
        String[] paths = new String[] { getRoutesPath(routerId),
                getRuleChainsPath(routerId), getSnatBlocksPath(routerId) };
        for (String path : paths) {
            Set<String> children;
            try {
                children = dir.getChildren(path, null);
            } catch (KeeperException e1) {
                continue;
            }
            for (String child : children) {
                try {
                    dir.delete(path + "/" + child);
                } catch (KeeperException e) {
                    continue;
                }
            }
            try {
                dir.delete(path);
            } catch (KeeperException e) {
                continue;
            }
        }
        dir.delete("/" + routerId.toString());
    }

    public Directory getRoutingTableDirectory(UUID routerId)
            throws KeeperException {
        return dir.getSubDirectory(getRoutingTablePath(routerId));
    }

    public void addRoute(UUID routerId, Route rt) throws KeeperException,
            InterruptedException {
        dir.add(getPathForRoute(routerId, rt), null, CreateMode.PERSISTENT);
    }

    public Collection<Route> getRoutes(UUID routerId) throws KeeperException,
            InterruptedException, NumberFormatException, UnknownHostException {
        Set<String> routeStrings = dir.getChildren(getRoutesPath(routerId),
                null);
        List<Route> routes = new Vector<Route>(routeStrings.size());
        for (String rtStr : routeStrings) {
            routes.add(Route.fromString(rtStr));
        }
        return routes;
    }

    public void deleteRoute(UUID routerId, Route rt) throws KeeperException,
            InterruptedException {
        dir.delete(getPathForRoute(routerId, rt));
    }

    private byte[] serializeRuleChain(List<Rule> rules) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ObjectOutputStream out = new ObjectOutputStream(bos);
        out.writeInt(rules.size());
        for (Rule r : rules) {
            out.writeObject(r);
        }
        out.close();
        return bos.toByteArray();
    }

    public void addRuleChain(UUID routerId, String chainName, List<Rule> rules)
            throws IOException, KeeperException, InterruptedException {
        dir.add(getPathForChain(routerId, chainName),
                serializeRuleChain(rules), CreateMode.PERSISTENT);
    }

    public void setRuleChain(UUID routerId, String chainName, List<Rule> rules)
            throws IOException, KeeperException, InterruptedException {
        dir.update(getPathForChain(routerId, chainName),
                serializeRuleChain(rules));
    }

    public Collection<String> getRuleChainNames(UUID routerId, Runnable watcher)
            throws KeeperException, InterruptedException {
        return dir.getChildren(getRuleChainsPath(routerId), watcher);
    }

    public List<Rule> getRuleChain(UUID routerId, String chainName,
            Runnable watcher) throws KeeperException, InterruptedException,
            IOException, ClassNotFoundException {
        byte[] data = dir.get(getPathForChain(routerId, chainName), watcher);
        ByteArrayInputStream bis = new ByteArrayInputStream(data);
        ObjectInputStream in = new ObjectInputStream(bis);
        int numRules = in.readInt();
        List<Rule> rules = new Vector<Rule>(numRules);
        for (int i = 0; i < numRules; i++)
            rules.add((Rule) in.readObject());
        return rules;
    }

    public void deleteRuleChain(UUID routerId, String chainName)
            throws KeeperException, InterruptedException {
        dir.delete(getPathForChain(routerId, chainName));
    }

    public Collection<NatTarget> getSnatBlocks(UUID routerId, Runnable watcher) {
        return null;
    }

    public void addSnatReservation(UUID routerId, NatTarget reservation) {

    }

}
