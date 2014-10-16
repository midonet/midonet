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

import java.util.Iterator;

import org.junit.Assert;
import org.junit.Test;
import org.midonet.packets.IPv4;
import org.midonet.packets.IPv4Addr;

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
        Iterable<IPv4Addr> descendants =
            InvalidationTrie.getAllDescendantsIpDestination(trie.dstPrefixTrie);
        Iterator<IPv4Addr> it = descendants.iterator();
        String ip1 = it.next().toString();
        Assert.assertEquals(ip1, ipRoute1);
        String ip2 = it.next().toString();
        Assert.assertEquals(ip2, ipRoute2);
        String ip3 = it.next().toString();
        Assert.assertEquals(ip3, ipRoute3);
    }
}
