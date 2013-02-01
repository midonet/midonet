/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package org.midonet.midolman.layer3;

import java.util.Iterator;

import org.junit.Assert;
import org.junit.Test;

import org.midonet.packets.IPv4;

public class TestInvalidationTrie {

    @Test
    public void testGetChildren() {
        InvalidationTrie trie = new InvalidationTrie();
        Route parent = new Route();
        String ipRoute1 = "11.11.2.5";
        parent.setDstNetworkAddr(ipRoute1);
        parent.dstNetworkLength = 32;
        trie.addRoute(parent);
        Route child2 = new Route();
        String ipRoute2 = "11.11.0.2";
        child2.setDstNetworkAddr(ipRoute2);
        child2.dstNetworkLength = 32;
        trie.addRoute(child2);
        Route child1 = new Route();
        String ipRoute3 = "11.11.0.1";
        child1.setDstNetworkAddr(ipRoute3);
        child1.dstNetworkLength = 32;
        trie.addRoute(child1);
        Iterable<Integer> descendants =
            InvalidationTrie.getAllDescendantsIpDestination(trie.dstPrefixTrie);
        Iterator<Integer> it = descendants.iterator();
        String ip1 = IPv4.fromIPv4Address(it.next()).toString();
        Assert.assertEquals(ip1, ipRoute1);
        String ip2 = IPv4.fromIPv4Address(it.next()).toString();
        Assert.assertEquals(ip2, ipRoute2);
        String ip3 = IPv4.fromIPv4Address(it.next()).toString();
        Assert.assertEquals(ip3, ipRoute3);
    }
}
