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
package org.midonet.api.system_data;

import java.net.URI;

import javax.ws.rs.core.MultivaluedMap;

import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.core.util.MultivaluedMapImpl;
import com.sun.jersey.test.framework.JerseyTest;

import org.junit.Before;
import org.junit.Test;

import org.midonet.api.rest_api.FuncTest;
import org.midonet.client.MidonetApi;
import org.midonet.client.VendorMediaType;
import org.midonet.client.resource.Router;
import org.midonet.cluster.backend.zookeeper.StateAccessException;
import org.midonet.cluster.data.SystemState.State;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.midonet.cluster.data.SystemState.Availability.READONLY;
import static org.midonet.cluster.data.SystemState.Availability.READWRITE;
import static org.midonet.cluster.data.SystemState.State.UPGRADE;

public class TestSystemState extends JerseyTest {

    private MidonetApi api;

    public TestSystemState() {
        super(FuncTest.appDesc);
    }

    @Before
    public void setUp() {

        resource().accept(VendorMediaType.APPLICATION_JSON_V5)
                .get(ClientResponse.class);
        URI baseUri = resource().getURI();
        api = new MidonetApi(baseUri.toString());
        api.enableLogging();
    }

    @Test
    public void testGetUpdate() throws StateAccessException {

        org.midonet.client.resource.SystemState systemState =
                api.getSystemState();
        assertThat("The state should be 'ACTIVE'",
                systemState.getState().equals(State.ACTIVE.toString()));
        assertThat("The Availability should be 'NORMAL'",
                systemState.getAvailability().equals(READWRITE.toString()));

        systemState.setAvailability(READONLY.toString());
        systemState.update();
        org.midonet.client.resource.SystemState systemState1
                = api.getSystemState();

        assertThat("The state should be 'ACTIVE'",
                   systemState1.getState().equals(State.ACTIVE.toString()));
        assertThat("The Availability should be 'LIMITED'",
                   systemState1.getAvailability().equals(READONLY.toString()));

        systemState.setState(UPGRADE.toString());
        systemState.update();
        org.midonet.client.resource.SystemState systemState2 =
            api.getSystemState();

        assertThat("The state should be 'UPGRADE'",
                   systemState2.getState().equals(UPGRADE.toString()));
        assertThat("The availability should be 'LIMITED'",
                systemState2.getAvailability().equals(READONLY.toString()));

        systemState.setAvailability(READWRITE.toString());
        systemState.update();
        org.midonet.client.resource.SystemState systemState3
                = api.getSystemState();

        assertThat("The state should be 'UPGRADE'",
                systemState3.getState().equals(UPGRADE.toString()));
        assertThat("The Availability should be 'NORMAL'",
                systemState3.getAvailability().equals(READWRITE.toString()));

        systemState.setState(State.ACTIVE.toString());
        systemState.update();
        org.midonet.client.resource.SystemState systemState4
                = api.getSystemState();

        assertThat("The state should be 'ACTIVE'",
                systemState4.getState().equals(State.ACTIVE.toString()));
        assertThat("The Availability should be 'NORMAL'",
                systemState4.getAvailability().equals(READWRITE.toString()));
    }

    @Test
    public void testReadOnlyMode() throws StateAccessException {
        org.midonet.client.resource.SystemState systemState =
                api.getSystemState();

        systemState.setAvailability(READWRITE.toString());
        systemState.update();

        // In NORMAL mode, we should be able to add routers.
        api.addRouter().tenantId("FAKE").name("SHOULDNOTEXIST").create();

        MultivaluedMap<String, String> qTenant1 = new MultivaluedMapImpl();
        qTenant1.add("tenant_id", "FAKE");

        org.midonet.client.resource.ResourceCollection<Router> routers
                = api.getRouters(qTenant1);

        assertThat("Routers should only have one item", routers.size() == 1);

        // In READ-ONLY mode, we should be able to GET routers, but not add any

        systemState.setAvailability(READONLY.toString());
        systemState.update();

        boolean exThrown = false;
        try {
            api.addRouter().tenantId("FAKE").name("SHOULDNOTEXIST_TWO").create();
        } catch (Exception ex) {
            exThrown = true;
        }
        assertThat("An exception should have been thrown", exThrown);
        routers = api.getRouters(qTenant1);

        assertThat("Routers should STILL have only one item", routers.size() == 1);

        // After allowing writes again, we should be able to add routers again.

        systemState.setAvailability(READWRITE.toString());
        systemState.update();

        api.addRouter().tenantId("FAKE").name("SHOULDNOTEXIST_TWO").create();

        routers = api.getRouters(qTenant1);

        assertThat("Routers should two items now", routers.size() == 2);

    }
}
