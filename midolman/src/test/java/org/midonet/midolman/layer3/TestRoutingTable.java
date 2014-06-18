/*
 * Copyright 2011 Midokura KK
 */

package org.midonet.midolman.layer3;

import java.net.UnknownHostException;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.UUID;
import java.util.Vector;

import org.junit.Assert;
import org.junit.Test;

import org.midonet.midolman.layer3.Route.NextHop;

public class TestRoutingTable {

    @Test
    public void testFindMSB() {
        Assert.assertEquals(32, RoutingTable.findMSB(0));
        Assert.assertEquals(31, RoutingTable.findMSB(0x01));
        Assert.assertEquals(28, RoutingTable.findMSB(0x0a));
        Assert.assertEquals(24, RoutingTable.findMSB(0xc3));
        Assert.assertEquals(22, RoutingTable.findMSB(0x03ff));
        Assert.assertEquals(17, RoutingTable.findMSB(0x70f0));
        Assert.assertEquals(8, RoutingTable.findMSB(0x8b5904));
        Assert.assertEquals(7, RoutingTable.findMSB(0x01c27d5f));
        Assert.assertEquals(4, RoutingTable.findMSB(0x0adedede));
        Assert.assertEquals(1, RoutingTable.findMSB(0x5f123456));
        Assert.assertEquals(0, RoutingTable.findMSB(0xb3020456));
    }

    @Test
    public void testEmptyRoutingTable() {
        RoutingTable table = new RoutingTable();
        Assert.assertFalse(table.lookup(0x0a010108, 0x0a010106).iterator().hasNext());
        Assert.assertFalse(table.lookup(0x00000009, 0xfffffffe).iterator().hasNext());
    }

    @Test
    public void testSameDstPrefixAndLength() throws UnknownHostException {
        Route rt1, rt2, rt3;
        rt1 = new Route(0, 0, 0x0a140085, 25, NextHop.PORT, new UUID((long) 40,
                (long) 50), 0, 100, null, null);
        rt2 = new Route(0xf01e0081, 0, 0x0a14008d, 25, NextHop.PORT, new UUID(
                (long) 40, (long) 60), 0, 200, null, null);
        rt3 = new Route(0x01020304, 0, 0x0a140090, 25, NextHop.PORT, new UUID(
                (long) 40, (long) 70), 0, 300, null, null);
        Set<Route> routes = new HashSet<Route>();
        routes.add(rt1);
        routes.add(rt2);
        routes.add(rt3);
        RoutingTable table = new RoutingTable();
        for (Route rt : routes)
            table.addRoute(rt);
        // Try a search with a bad address.
        Set<Route> matches = generateSet(table.lookup(0x12345678,
                0xddddeeee));
        Assert.assertEquals(0, matches.size());
        // Now try a matching address.
        matches = generateSet(table.lookup(0xddddeeee, 0x0a140080));
        Assert.assertEquals(1, matches.size());
        Assert.assertTrue(matches.contains(rt1));
        // Reinsert the other routes with equal priority to rt1.
        table.deleteRoute(rt2);
        table.deleteRoute(rt3);
        rt2.weight = 100;
        rt3.weight = 100;
        table.addRoute(rt2);
        table.addRoute(rt3);

        matches = generateSet(table.lookup(0x12345678, 0x0a140080));
        Assert.assertEquals(3, matches.size());
        Assert.assertTrue(matches.contains(rt1));
        Assert.assertTrue(matches.contains(rt2));
        Assert.assertTrue(matches.contains(rt3));
        // Now change the src network length of rt2
        table.deleteRoute(rt2);
        rt2.srcNetworkLength = 16;
        table.addRoute(rt2);
        matches = generateSet(table.lookup(0x12345678, 0x0a140080));
        Assert.assertEquals(2, matches.size());
        Assert.assertTrue(matches.contains(rt1));
        Assert.assertTrue(matches.contains(rt3));

        // Now remove rt1
        table.deleteRoute(rt1);
        matches = generateSet(table.lookup(0x12345678, 0x0a140080));
        Assert.assertEquals(1, matches.size());
        Assert.assertTrue(matches.contains(rt3));
        // Now remove rt3 and try src ip that matches rt2 in 16 bits.
        table.deleteRoute(rt3);
        matches = generateSet(table.lookup(0xf01e0081, 0x0a140080));
        Assert.assertEquals(1, matches.size());
        Assert.assertTrue(matches.contains(rt2));
        // Finally delete rt2, then re-add rt1, and do the lookup again.
        table.deleteRoute(rt2);
        table.addRoute(rt1);
        matches = generateSet(table.lookup(0xf01e0081, 0x0a140080));
        Assert.assertEquals(1, matches.size());
        Assert.assertTrue(matches.contains(rt1));
    }

    @Test
    public void testSameDstPrefixVaryingLengths() throws UnknownHostException {
        Route rt1, rt2, rt3, rt4, rt5;
        rt1 = new Route(0, 0, 0x0a140085, 7, NextHop.PORT, new UUID((long) 40,
                (long) 50), 0, 100, null, null);
        rt2 = new Route(0, 0, 0x0a14008d, 18, NextHop.PORT, new UUID((long) 40,
                (long) 60), 0, 200, null, null);
        rt3 = new Route(0, 0, 0x0a14007d, 18, NextHop.PORT, new UUID((long) 40,
                (long) 70), 0, 300, null, null);
        rt4 = new Route(0, 0, 0x0a140096, 25, NextHop.PORT, new UUID((long) 40,
                (long) 80), 0, 300, null, null);
        rt5 = new Route(0x80c00304, 10, 0x0a14009f, 28, NextHop.PORT, new UUID(
                (long) 40, (long) 80), 0, 300, null, null);
        Set<Route> routes = new HashSet<Route>();
        routes.add(rt1);
        routes.add(rt2);
        routes.add(rt3);
        routes.add(rt4);
        routes.add(rt5);
        RoutingTable table = new RoutingTable();
        for (Route rt : routes)
            table.addRoute(rt);

        // Use a dst ip that matches rt4 but not rt5.
        Set<Route> matches = generateSet(table.lookup(0x12345678,
                0x0a140080));
        Assert.assertEquals(1, matches.size());
        Assert.assertTrue(matches.contains(rt4));
        // Now try to match rt5 - note that src must match 10 bits
        matches = generateSet(table.lookup(0x80c01234, 0x0a140090));
        Assert.assertEquals(1, matches.size());
        Assert.assertTrue(matches.contains(rt5));
        // Now show that if the src doesn't match we end up with rt4 instead.
        matches = generateSet(table.lookup(0x80a01122, 0x0a140090));
        Assert.assertEquals(1, matches.size());
        Assert.assertTrue(matches.contains(rt4));
        // Now use a dst ip that matches rt2 and rt3, but rt2 has lower weight.
        matches = generateSet(table.lookup(0x12345678, 0x0a143700));
        Assert.assertEquals(1, matches.size());
        Assert.assertTrue(matches.contains(rt2));
        // Now match only rt1
        matches = generateSet(table.lookup(0x12345678, 0x0b332211));
        Assert.assertEquals(1, matches.size());
        Assert.assertTrue(matches.contains(rt1));
        // Now remove rt4 and try again with its address.
        table.deleteRoute(rt4);
        matches = generateSet(table.lookup(0x12345678, 0x0a140080));
        Assert.assertEquals(1, matches.size());
        Assert.assertTrue(matches.contains(rt2));
        // Remove and re-add rt3 equal weight as rt2.
        table.deleteRoute(rt3);
        rt3.weight = 200;
        table.addRoute(rt3);
        // Try rt4's match again.
        matches = generateSet(table.lookup(0x12345678, 0x0a140080));
        Assert.assertEquals(2, matches.size());
        Assert.assertTrue(matches.contains(rt2));
        Assert.assertTrue(matches.contains(rt3));
        // Now remove rt1 and try again with an address that matches it.
        table.deleteRoute(rt1);
        matches = generateSet(table.lookup(0x12345678, 0x0b332211));
        Assert.assertEquals(0, matches.size());
        // Try to match rt5 again - again note that src must match 10 bits
        matches = generateSet(table.lookup(0x80c01234, 0x0a140090));
        Assert.assertEquals(1, matches.size());
        Assert.assertTrue(matches.contains(rt5));
        // Remove rt5, the same lookup should return rt2 and rt3
        table.deleteRoute(rt5);
        matches = generateSet(table.lookup(0x80c01234, 0x0a140090));
        Assert.assertEquals(2, matches.size());
        Assert.assertTrue(matches.contains(rt2));
        Assert.assertTrue(matches.contains(rt3));
        // Re-add rt1 and rt5, remove rt2 and rt3
        table.addRoute(rt1);
        table.addRoute(rt5);
        table.deleteRoute(rt2);
        table.deleteRoute(rt3);
        // Try to match rt5 again - again note that src must match 10 bits
        matches = generateSet(table.lookup(0x80c01234, 0x0a140090));
        Assert.assertEquals(1, matches.size());
        Assert.assertTrue(matches.contains(rt5));
        // Now try to match rt4 (still deleted), this time it matches rt1.
        matches = generateSet(table.lookup(0x12345678, 0x0a140080));
        Assert.assertEquals(1, matches.size());
        Assert.assertTrue(matches.contains(rt1));
    }

    @Test
    public void testManyPrefixesManyLengths() throws UnknownHostException {
        Vector<Route> routes = new Vector<Route>();
        routes.add(new Route(0, 0, 0, 0, NextHop.PORT, new UUID((long) 40,
                (long) 50), 0, 100, null, null));
        RoutingTable table = new RoutingTable();
        for (Route rt : routes)
            table.addRoute(rt);
    }
    
    private Set<Route> generateSet(Iterable<Route> iterable){
        Set<Route> set = new HashSet<Route>();
        for(Iterator<Route> it = iterable.iterator(); it.hasNext();){
            set.add(it.next());
        }
        return set;
    }
}
