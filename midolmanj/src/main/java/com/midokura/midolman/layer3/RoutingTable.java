/*
 * Copyright 2011 Midokura KK
 */

package com.midokura.midolman.layer3;

import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.Vector;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.packets.Net;

public class RoutingTable {

    private final static Logger log = LoggerFactory.getLogger(RoutingTable.class);

    private TrieNode dstPrefixTrie;
    private int numRoutes = 0;

    public void clear() {
        dstPrefixTrie = null;
    }

    public Iterable<Route> lookup(int src, int dst) {
        log.debug("lookup: src {} dst {} in table with {} routes",
                new Object[] {
                    Net.convertIntAddressToString(src),
                    Net.convertIntAddressToString(dst),
                    numRoutes} );

        List<Route> ret = new Vector<Route>();
        Iterator<Collection<Route>> rtIter = findBestMatch(dst);
        while (rtIter.hasNext()) {
            Collection<Route> routes = rtIter.next();
            int minWeight = Integer.MAX_VALUE;
            // Filter out the routes that don't match the source address and
            // return only those with the minimum weight.
            ret.clear();
            for (Route rt : routes) {
                if (addrsMatch(src, rt.srcNetworkAddr, rt.srcNetworkLength)) {
                    if (rt.weight < minWeight) {
                        ret.clear();
                        ret.add(rt);
                        minWeight = rt.weight;
                    } else if (rt.weight == minWeight)
                        ret.add(rt);
                }
            }
            if (ret.size() > 0)
                break;
        }

        log.debug("lookup: return {} for src {} dst {}",
                new Object[] {
                ret,
                Net.convertIntAddressToString(src),
                Net.convertIntAddressToString(dst)});

        return ret;
    }

    private static class TrieNode {
        int bitlen;
        int addr;
        TrieNode parent;
        TrieNode left;
        TrieNode right;
        Set<Route> routes;

        TrieNode(TrieNode parent, int bitlen, int addr) {
            this.parent = parent;
            this.bitlen = bitlen;
            this.addr = addr;
            routes = new HashSet<Route>();
        }

        @Override
        public String toString() {
            return "TrieNode [bitlen=" + bitlen + ", addr=" + addr + ", left=" + left
                    + ", right=" + right + ", routes=" + routes + "]";
        }
    }

    public static boolean addrsMatch(int addr1, int addr2, int bitlen) {
        if (bitlen <= 0)
            return true;
        int xor = addr1 ^ addr2;
        int shift = 32 - bitlen;
        if (shift <= 0)
            return 0 == xor;
        return 0 == (xor >>> shift);
    }

    // This table gives the position of the most significant set bit for each
    // value from 0 to 255.
    static final int[] MSB = new int[256];
    static {
        int i, j, numToSet;
        MSB[0] = 32;
        for (i = 0; i < 8; i++) {
            numToSet = 0x01 << i;
            for (j = 0; j < numToSet; j++)
                MSB[numToSet + j] = 7 - i;
        }
    }

    /**
     * Returns the index of the most significant set bit (left to right).
     * Equivalently, the number of leading zeros in the 2's complement.
     *
     * @param v
     * @return
     */
    public static int findMSB(int v) {
        //* Add/remove '/' at the start of this line to toggle implementations.
        return Integer.numberOfLeadingZeros(v);
        /*/
        // Custom implementation:
        // This is fast without being clever. It uses more space than algorithms
        // that use binary magic numbers or De Bruijn sequences but we can
        // afford the 1kb (255 integers). For this and other approaches see:
        // http://graphics.stanford.edu/~seander/bithacks.html#IntegerLogLookup
        // "Bit Twiddling Hacks" by Sean Eron Anderson of Stanford University.
        if (0 == v)
            return MSB[0];
        for (int i = 3; i > 0; i--) {
            int shift = i * 8;
            int b = v >>> shift;
            if (0 != b)
                return 24 - shift + MSB[b & 0xff];
        }
        return 24 + MSB[v & 0xff];
        //*/
    }

    public void addRoute(Route rt) {
        log.debug("addRoute: {}", rt);
        numRoutes++;

        TrieNode parent = null;
        boolean inLeftChild = false;
        TrieNode node = dstPrefixTrie;
        int rt_dst = rt.dstNetworkAddr;

        while (null != node && rt.dstNetworkLength >= node.bitlen
                && addrsMatch(rt_dst, node.addr, node.bitlen)) {
            // The addresses match, descend to the children.
            if (rt.dstNetworkLength == node.bitlen) {
                // Exact match. Add the route to this node's set.
                node.routes.add(rt);
                return;
            }
            // Use bit at position bitlen to decide on left or right branch.
            inLeftChild = 0 == (rt_dst & (0x80000000 >>> node.bitlen));
            parent = node;
            node = (inLeftChild) ? node.left : node.right;
        }
        // We set the new node's parent, but might change it below.
        TrieNode newNode = new TrieNode(parent, rt.dstNetworkLength, rt_dst);
        newNode.routes.add(rt);
        if (null != node) {
            // Find the first bit in which rt_dst and node.addr differ.
            // TODO(pino): can start checking bits after parent.bitlen.
            int diffBit = findMSB(node.addr ^ rt_dst);
            /*
             * Only 2 cases to consider: 1) diffBit less than both
             * newNode.bitlen and node.bitlen 2) newNode.bitlen less than both
             * bitDiff and node.bitlen The following case is NOT possible here
             * because of the previous while loop's condition: 3) node.bitlen
             * less than both newNode.bitlen and diffBit In case 1, create a
             * parent node for both node and newNode. In case 2, make newNode
             * the parent of node.
             */
            if (diffBit < node.bitlen && diffBit < newNode.bitlen) { // Case 1
                TrieNode newParent = new TrieNode(parent, diffBit, rt_dst);
                int bit = rt_dst & (0x80000000 >>> diffBit);
                newParent.left = (0 == bit) ? newNode : node;
                newParent.right = (0 == bit) ? node : newNode;
                node.parent = newParent;
                newNode.parent = newParent;
                newNode = newParent;
            } else { // newNode.bitlen < diffBit && newNode.bitlen < node.bitlen
                     // Should node be the left or right child of newNode?
                int bit = node.addr & (0x80000000 >>> newNode.bitlen);
                if (0 == bit)
                    newNode.left = node;
                else
                    newNode.right = node;
                node.parent = newNode;
            }
        }
        if (null == parent)
            dstPrefixTrie = newNode;
        else if (inLeftChild)
            parent.left = newNode;
        else
            parent.right = newNode;
    }

    public void deleteRoute(Route rt) {
        log.debug("deleteRoute: {}", rt);
        numRoutes--;

        TrieNode parent = null;
        boolean inLeftChild = false;
        TrieNode node = dstPrefixTrie;
        int rt_dst = rt.dstNetworkAddr;

        while (null != node && rt.dstNetworkLength >= node.bitlen
                && addrsMatch(rt_dst, node.addr, node.bitlen)) {
            // The addresses match, descend to the children.
            if (rt.dstNetworkLength == node.bitlen) {
                // Exact match. Remove the route from this node's set.
                node.routes.remove(rt);
                // If the node has no routes and has only one child, we can
                // remove it. Having done so, we can do the same check on its
                // parent (and so on up).
                while (null != node && node.routes.isEmpty()) {
                    if (null == node.left) {
                        if (null == parent)
                            dstPrefixTrie = node.right;
                        else if (inLeftChild)
                            parent.left = node.right;
                        else
                            parent.right = node.right;
                    } else if (null == node.right) {
                        if (null == parent)
                            dstPrefixTrie = node.left;
                        else if (inLeftChild)
                            parent.left = node.left;
                        else
                            parent.right = node.left;
                    } else
                        return; // We're keeping the node.
                    node = parent;
                    parent = (null == node) ? null : node.parent;
                }
                return;
            }
            // Use bit at position bitlen to decide on left or right branch.
            inLeftChild = 0 == (rt_dst & (0x80000000 >>> node.bitlen));
            parent = node;
            node = (inLeftChild) ? node.left : node.right;
        }
    }

    private class MyRoutesIterator implements Iterator<Collection<Route>> {
        TrieNode node;

        MyRoutesIterator(TrieNode node) {
            this.node = node;
        }

        @Override
        public boolean hasNext() {
            return (null != node);
        }

        @Override
        public Collection<Route> next() {
            if (null == node)
                throw new NoSuchElementException("No more routes.");
            // TODO(pino): should we clone the routes collection?
            Collection<Route> routes = null;
            while (null != node) {
                routes = node.routes;
                node = node.parent;
                if (routes.isEmpty())
                    continue;
                else
                    break;
            }
            return routes;
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException("Can't remove routes.");
        }
    }

    private Iterator<Collection<Route>> findBestMatch(int dst) {
        TrieNode parent = null;
        TrieNode node = dstPrefixTrie;

        while (null != node && addrsMatch(dst, node.addr, node.bitlen)) {
            // The addresses match, descend to the children.
            // Use bit at position bitlen to decide on left or right branch.
            boolean goLeft = 0 == (dst & (0x80000000 >>> node.bitlen));
            parent = node;
            node = (goLeft) ? node.left : node.right;
        }
        return new MyRoutesIterator(parent);
    }

    @Override
    public String toString() {
        return "RoutingTable [dstPrefixTrie=" + dstPrefixTrie + "]";
    }
}
