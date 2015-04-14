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

package org.midonet.midolman.layer3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Stack;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.packets.IPv4Addr;
import org.midonet.packets.IPv4Subnet;

public class InvalidationTrie extends RoutesTrie {

    private final static Logger log = LoggerFactory.getLogger(
        "org.midonet.devices.router");

    /**
     * Returns all destination IP addresses that share the same prefix of the
     * given invalidation trie node.
     *
     * TODO(ross) If in the routing table there's a route that is a child of the
     * route corresponding to the node we pass, we shouldn't invalidate the
     * destination IPs that are below this more specific route.
     */
    public static Iterable<IPv4Addr>
        getAllDescendantsIpDestination(RoutesTrie.TrieNode node){
        if (node == null)
            return Collections.emptyList();
        List<IPv4Addr> destIps = new ArrayList<>();
        Stack<TrieNode> stack = new Stack<>();
        stack.add(node);
        while(!stack.empty()){
            TrieNode n = stack.pop();
            for(Route route: n.getRoutes()){
                destIps.add(IPv4Addr.fromInt(route.dstNetworkAddr));
            }
            if(null != n.left)
                stack.add(n.left);
            if(null != n.right)
                stack.add(n.right);
        }
        return destIps;
    }

    /**
     * Returns the invalidation trie node that corresponds to the destination
     * network (address and prefix) of a given route. This node can be used
     * with the getAllDescendantsIpDestination() method to determine all
     * destination IP addresses that share the same prefix represented by this
     * node, and should be invalidated when the route has changed.
     */
    public RoutesTrie.TrieNode projectRouteAndGetSubTree(Route rt) {

        boolean inLeftChild;
        RoutesTrie.TrieNode node = dstPrefixTrie;
        int rtDst = rt.dstNetworkAddr;
        log.debug("Root {}, # roots {}", dstPrefixTrie, numRoutes);
        while (null != node && rt.dstNetworkLength >= node.bitlen
            && IPv4Subnet.addrMatch(rtDst, node.addr, node.bitlen)) {
            log.debug("traversing {}", node);
            // The addresses match, descend to the children.
            if (rt.dstNetworkLength == node.bitlen) {
                // TODO(rossella) be more precise in future. If the route added
                // is more specific for some flow than the one in the set,
                // invalidate those flows. Eg. src routing
                return node;
            }
            // Use bit at position bitlen to decide on left or right branch.
            inLeftChild = 0 == (rtDst & (0x80000000 >>> node.bitlen));
            node = (inLeftChild) ? node.left : node.right;
        }

        if (null != node) {

            /* Only 2 cases to consider (see addTag for a longer analysis)
            1. the node that would hold this route is a sibling of node and we'd
               need to create a node to be the parent of both
            2. the node that would hold this route should be the parent of node

            For case 1 the subtree empty. For case 2 the subtree is the tree
            whose root is node.  */
            int diffBit = findMSB(node.addr ^ rtDst);
            // Case 1
            /*if (diffBit < node.bitlen && diffBit < rt.dstNetworkLength) { // Case 1
                return null;
            }*/
            // Case 2
            if (rt.dstNetworkLength < diffBit && rt.dstNetworkLength < node.bitlen) {
                return node;
            }

        }
        // The trie is empty or subtree is empty
        return null;
    }

    @Override
    protected Logger getLog() {
        return log;
    }

    @Override
    public String toString() {
        return "InvalidationTrie [dstPrefixTrie=" + dstPrefixTrie + "]";
    }

}
