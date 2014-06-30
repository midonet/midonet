/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.midolman.state;

import org.apache.zookeeper.KeeperException;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Base class for Zookeeper exceptions arising from a node either
 * existing when not expected (StatePathExistsException) or not
 * existing when expected (NoStatePathException). Has functionality
 * for identifying the resource associated with the path.
 */
public abstract class StatePathExceptionBase extends StateAccessException {

    private static final long serialVersionUID = 1L;

    public enum NodeType {
        HEALTH_MONITOR("health monitor", "health_monitors"),
        LOAD_BALANCER("load balancer", "load_balancers"),
        POOL("pool", "pools"),
        POOL_MEMBER("pool member", "pool_members"),
        VIP("VIP", "vips"),
        TUNNEL_ZONE("tunnel zone", "tunnel_zone");

        // Pretty name for error messages.
        public final String name;

        // How we represent the node type in a Zookeeper path.
        private final String path;

        private NodeType(String name, String path) {
            this.name = name;
            this.path = path;
        }

        static Map<String, NodeType> map = new HashMap<>();
        static {
            for (NodeType nodeType : values())
                map.put(nodeType.path, nodeType);
        }

        static protected NodeType fromPath(String path) {
            return map.get(path);
        }
    }

    public class NodeInfo {
        public final NodeType nodeType;
        public final UUID id;

        public NodeInfo(NodeType nodeType, UUID id) {
            this.nodeType = nodeType;
            this.id = id;
        }
    }

    // Path to node whose (non)existence caused the exception.
    protected final String path;

    // Base Zookeeper path.
    private final String basePath;

    // Cache NodeInfo when it's constructed.
    protected NodeInfo nodeInfo = null;

    public StatePathExceptionBase(String message, String path,
                                  String basePath, KeeperException cause) {
        super(message, cause);
        this.path = path;
        this.basePath = basePath;
    }

    /**
     * Provided for TunnelZoneZkManager(), which generates a
     * StatePathExistsException without an underlying KeeperException.
     */
    public StatePathExceptionBase(String message) {
        super(message);
        this.path = null;
        this.basePath = null;
    }

    /**
     * Returns the path to the node whose (non)existence caused the exception.
     */
    public String getPath() {
        return path;
    }

    /**
     * Gets the ID and type of resource referred to by this exception's path.
     * NB: I only implemented as much of this function as I actually needed.
     * Additional work may be needed to handle other cases.
     */
    public NodeInfo getNodeInfo() {
        if (nodeInfo != null)
            return nodeInfo;

        // Make sure path starts with basePath.
        String normalizedBasePath =
                basePath.endsWith("/") ? basePath : basePath + "/";
        if (path.length() < normalizedBasePath.length() ||
                !path.startsWith(basePath))
            throw new IllegalStateException(
                    "Path does not start with basePath.", this);

        String relativePath = path.substring(normalizedBasePath.length());
        Deque<String> steps =
                new ArrayDeque<>(Arrays.asList(relativePath.split("/")));

        // Can't think of a good reason for this to happen.
        if (steps.isEmpty())
            throw new IllegalStateException("RelativePath is empty.", this);

        // Assuming path starts with type/UUID,
        // e.g. "vips/5e2b4760-7ce0-11e3-baa7-0800200c9a66"
        String typeStep = steps.pop();
        NodeType nodeType = NodeType.fromPath(typeStep);
        if (nodeType == null)
            throw new IllegalStateException(
                    "Unexpected type step '" + typeStep + "' in path: " + path, this);

        UUID id = popUuid(steps);
        if (id == null) {
            throw new IllegalStateException(
                    "Expected UUID after step '" + typeStep + "' in path: " + path, this);
        }

        // May need to relax this assumption at some point. Obviously
        // we do have paths with more than two steps (e.g.,
        // "pools/{UUID}/vips/{UUID}"), but I don't know if we'll ever
        // need to parse one for an error message.
        if (!steps.isEmpty())
            throw new IllegalStateException(
                    "Unexpected continuation of path after first UUID: " + path, this);

        // Cache for future calls.
        nodeInfo = new NodeInfo(nodeType, id);
        return nodeInfo;
    }

    /**
     * Attempts to convert the first String in steps to a UUID.
     * Consumes the String and returns the UUID if successful,
     * otherwise returns null and leaves steps unaltered.
     */
    private UUID popUuid(Deque<String> steps) {
        String next = steps.peek();
        if (next == null || next.length() != 36)
            return null;
        try {
            UUID id = UUID.fromString(next);
            steps.pop();
            return id;
        } catch (Exception ex) {
            return null;
        }
    }
}
