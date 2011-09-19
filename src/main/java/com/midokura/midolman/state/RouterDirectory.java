package com.midokura.midolman.state;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStreamWriter;
import java.net.UnknownHostException;
import java.util.Collection;
import java.util.List;
import java.util.NavigableSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.Vector;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.KeeperException.NoNodeException;
import org.apache.zookeeper.KeeperException.NodeExistsException;
import org.codehaus.jackson.JsonFactory;
import org.codehaus.jackson.JsonGenerator;
import org.codehaus.jackson.JsonParser;
import org.codehaus.jackson.annotate.JsonAutoDetect.Visibility;
import org.codehaus.jackson.map.ObjectMapper;

import com.midokura.midolman.layer3.Route;
import com.midokura.midolman.rules.Rule;

public class RouterDirectory {

    Directory dir;

    private static ObjectMapper objectMapper = new ObjectMapper();
    private static JsonFactory jsonFactory = new JsonFactory(objectMapper);

    static {
        objectMapper.setVisibilityChecker(objectMapper.getVisibilityChecker()
                .withFieldVisibility(Visibility.ANY));
        objectMapper.enableDefaultTyping(ObjectMapper.DefaultTyping.NON_FINAL);
    }

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

    public void addRouter(UUID routerId)
            throws InterruptedException, KeeperException, IOException {
        // Use try-catch blocks to avoid getting stuck in a half-created state.
        try {
            dir.add("/" + routerId.toString(), null, CreateMode.PERSISTENT);
        } catch (KeeperException e) {}
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

    public boolean exists(UUID routerId) throws KeeperException,
            InterruptedException {
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
        JsonGenerator jsonGenerator = jsonFactory
                .createJsonGenerator(new OutputStreamWriter(out));
        jsonGenerator.writeObject(rules);
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
        JsonParser jsonParser = jsonFactory
                .createJsonParser(new InputStreamReader(in));
        List<Rule> rules = jsonParser.readValueAs(List.class);
        return rules;
    }

    public void deleteRuleChain(UUID routerId, String chainName)
            throws KeeperException, InterruptedException {
        dir.delete(getPathForChain(routerId, chainName));
    }
}
