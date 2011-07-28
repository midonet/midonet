package com.midokura.midolman.state;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException.NoNodeException;
import org.apache.zookeeper.KeeperException.*;

import com.midokura.midolman.*;

public class MockDirectory implements Directory {

    private class Node {
        // The node's path from the root.
        private String path;
        private byte[] data;
        CreateMode mode;
        int sequence;
        Map<String, Node> children;
        Set<Runnable> dataWatchers;
        Set<Runnable> childrenWatchers;
        // We currently have no need for Watchers on 'exists'.

        Node(String path, byte[] data, CreateMode mode) {
            super();
            this.path = path;
            this.data = data;
            this.mode = mode;
            this.sequence = 0;
            this.children = new HashMap<String, Node>();
            this.dataWatchers = new HashSet<Runnable>();
            this.childrenWatchers = new HashSet<Runnable>();
        }

        Node getChild(String name) throws NoNodeException {
            Node child = children.get(name);
            if (null == child)
                throw new NoNodeException(path + '/' + name);
            return child;
        }

        // Document that this returns the absolute path of the child
        String addChild(String name, byte[] data, CreateMode mode) 
                throws NodeExistsException, NoChildrenForEphemeralsException {
            if (!mode.isSequential() && children.containsKey(name))
                throw new NodeExistsException(path + '/' + name);
            if (this.mode.isEphemeral())
                throw new NoChildrenForEphemeralsException(path + '/' + name);
            if (mode.isSequential()) {
                name = new StringBuilder(name).append(sequence++).toString();
            }
            String childPath = new StringBuilder(path).append("/").
                    append(name).toString();
            Node child = new Node(childPath, data, mode);
            children.put(name, child);
            notifyChildrenWatchers();
            return childPath;
        }

        void setData(byte[] data) {
            this.data = data;
            notifyDataWatchers();
        }

        byte[] getData(Runnable watcher) {
            if (watcher != null)
                dataWatchers.add(watcher);
            return data.clone();
        }

        Set<String> getChildren(Runnable watcher) {
            if (watcher != null)
                childrenWatchers.add(watcher);
            return new HashSet<String>(children.keySet());
        }

        void deleteChild(String name) throws NoNodeException {
            Node child = children.get(name);
            if (null == child)
                throw new NoNodeException(new StringBuilder(path).append("/").
                        append(name).toString());
            children.remove(name);
            child.notifyDataWatchers();
            this.notifyChildrenWatchers();
        }

        void notifyChildrenWatchers() {
            // Each Watcher is called back at most once for every time they
            // register.
            Set<Runnable> watchers = childrenWatchers;
            childrenWatchers = new HashSet<Runnable>();
            for (Runnable watcher : watchers) {
                watcher.run();
            }
        }

        void notifyDataWatchers() {
            // Each Watcher is called back at most once for every time they
            // register.
            Set<Runnable> watchers = dataWatchers;
            dataWatchers = new HashSet<Runnable>();
            for (Runnable watcher : watchers) {
                watcher.run();
            }
        }
    }

    private Node rootNode;
    
    public MockDirectory() {
        rootNode = new Node("", null, CreateMode.PERSISTENT);
    }

    private MockDirectory(Node root) {
        rootNode = root;
    }

    private Node getNode(String path) throws NoNodeException {
        String[] path_elems = path.split("/");
        return getNode(path_elems, path_elems.length);
    }
    
    private Node getNode(String[] path, int depth) throws NoNodeException {
        Node curNode = rootNode;
        // TODO(pino): fix this hack - starts at 1 to skip empty string.
        for (int i = 1; i < depth; i++) {
            String path_elem = path[i];
            curNode = curNode.getChild(path_elem);
        }
        return curNode;
    }

    @Override
    public String add(String relativePath, byte[] data, CreateMode mode) 
            throws NoNodeException, NodeExistsException, 
            NoChildrenForEphemeralsException {
        if (!relativePath.startsWith("/")) {
            throw new IllegalArgumentException("Path must start with '/'");
        }
        String[] path = relativePath.split("/");
        if (path.length == 0)
            throw new IllegalArgumentException("Cannot add the root node");
        Node parent = getNode(path, path.length-1);
        String childPath = parent.addChild(path[path.length-1], data, mode);
        return childPath.substring(rootNode.path.length());
    }

    @Override
    public void update(String path, byte[] data) throws NoNodeException {
        getNode(path).setData(data);
    }

    @Override
    public byte[] get(String path, Runnable watcher) 
            throws NoNodeException {
        return getNode(path).getData(watcher);
    }

    @Override
    public Set<String> getChildren(String path, Runnable watcher) 
            throws NoNodeException {
        return getNode(path).getChildren(watcher);
    }

    @Override
    public boolean has(String path) {
        try {
            getNode(path);
            return true;
        }
        catch (NoNodeException e) {
            return false;
        } 
    }

    @Override
    public void delete(String relativePath) throws NoNodeException {
        String[] path = relativePath.split("/");
        if (path.length == 0)
            throw new IllegalArgumentException("Cannot delete the root node");
        Node parent = getNode(path, path.length-1);
        parent.deleteChild(path[path.length-1]);
    }

    @Override
    public Directory getSubDirectory(String path)
            throws NoNodeException {
        Node subdirRoot = getNode(path);
        return new MockDirectory(subdirRoot);
    }
}
