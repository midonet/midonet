/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.data.dto;

import java.util.Arrays;
import java.util.Collection;
import java.util.UUID;

import junit.framework.Assert;

import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import com.midokura.midolman.layer3.Route.NextHop;

@RunWith(Enclosed.class)
public class TestRoute {

    @RunWith(Parameterized.class)
    public static class TestObjectCreation {

        private final UUID inputId;
        private final com.midokura.midolman.layer3.Route inputRoute;
        private final Route expected;

        public TestObjectCreation(UUID inputId,
                com.midokura.midolman.layer3.Route inputRoute, Route expected) {
            this.inputId = inputId;
            this.inputRoute = inputRoute;
            this.expected = expected;
        }

        private static Route createExpectedRoute(UUID id, String srcAddress,
                int srcAddressLen, String dstAddress, int dstAddressLen,
                String type, UUID nextHopPort, String nextHopGateway,
                int weight, UUID routerId, String attibute) {
            Route route = new Route();
            route.setId(id);
            route.setSrcNetworkAddr(srcAddress);
            route.setSrcNetworkLength(srcAddressLen);
            route.setDstNetworkAddr(dstAddress);
            route.setDstNetworkLength(dstAddressLen);
            route.setType(type);
            route.setNextHopPort(nextHopPort);
            route.setNextHopGateway(nextHopGateway);
            route.setWeight(weight);
            route.setRouterId(routerId);
            route.setAttributes(attibute);
            return route;
        }

        @Parameters
        public static Collection<Object[]> input() {

            UUID id = UUID.randomUUID();
            UUID nextHopPortId = UUID.randomUUID();
            UUID routerId = UUID.randomUUID();
            com.midokura.midolman.layer3.Route normalInput = new com.midokura.midolman.layer3.Route(
                    167772163, 24, 167772164, 24, NextHop.PORT, nextHopPortId,
                    167772161, 100, "foo", routerId);
            Route normalExpected = createExpectedRoute(id, "10.0.0.3", 24,
                    "10.0.0.4", 24, Route.Normal, nextHopPortId, "10.0.0.1",
                    100, routerId, "foo");

            com.midokura.midolman.layer3.Route noGatewayInput = new com.midokura.midolman.layer3.Route(
                    167772163, 24, 167772164, 24, NextHop.PORT, nextHopPortId,
                    com.midokura.midolman.layer3.Route.NO_GATEWAY, 100, "foo",
                    routerId);
            Route noGatewayExpected = createExpectedRoute(id, "10.0.0.3", 24,
                    "10.0.0.4", 24, Route.Normal, nextHopPortId, null, 100,
                    routerId, "foo");

            Object[][] data = new Object[][] {
                    { id, normalInput, normalExpected },
                    { id, noGatewayInput, noGatewayExpected } };

            return Arrays.asList(data);
        }

        @Test
        public void testAllFieldsMatch() {

            // Execute
            Route actual = new Route(inputId, inputRoute);

            // Verify
            Assert.assertEquals(expected.getId(), inputId);
            Assert.assertEquals(expected.getAttributes(),
                    actual.getAttributes());
            Assert.assertEquals(expected.getDstNetworkAddr(),
                    actual.getDstNetworkAddr());
            Assert.assertEquals(expected.getDstNetworkLength(),
                    actual.getDstNetworkLength());
            Assert.assertEquals(expected.getNextHopGateway(),
                    actual.getNextHopGateway());
            Assert.assertEquals(expected.getSrcNetworkAddr(),
                    actual.getSrcNetworkAddr());
            Assert.assertEquals(expected.getSrcNetworkLength(),
                    actual.getSrcNetworkLength());
            Assert.assertEquals(expected.getType(), actual.getType());
            Assert.assertEquals(expected.getWeight(), actual.getWeight());
            Assert.assertEquals(expected.getNextHopPort(),
                    actual.getNextHopPort());
            Assert.assertEquals(expected.getRouterId(), actual.getRouterId());
        }
    }
}
