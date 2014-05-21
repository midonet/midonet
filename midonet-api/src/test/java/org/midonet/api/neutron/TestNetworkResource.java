/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.api.neutron;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.midonet.api.ResourceTest;
import org.midonet.api.rest_api.ConflictHttpException;
import org.midonet.api.rest_api.NotFoundHttpException;
import org.midonet.cluster.data.neutron.Network;
import org.midonet.midolman.state.NoStatePathException;
import org.midonet.midolman.state.StatePathExistsException;
import org.mockito.runners.MockitoJUnitRunner;

import javax.ws.rs.core.Response;
import java.util.UUID;

import static org.mockito.Matchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;

@RunWith(MockitoJUnitRunner.class)
public class TestNetworkResource extends ResourceTest {

    private NetworkResource testObject;

    public static Network network() {
        return network(UUID.randomUUID());
    }

    public static Network network(UUID id) {
        Network net = new Network();
        net.id = id;
        return net;
    }

    @Before
    public void setUp() throws Exception {

        super.setUp();

        testObject = new NetworkResource(config, uriInfo, context, plugin);
    }

    @Test
    public void testCreate() throws Exception {

        Network input = network();
        Network output = network(input.id);

        doReturn(output).when(plugin).createNetwork(input);

        Response resp = testObject.create(input);

        assertCreate(resp, output,
                NeutronUriBuilder.getNetwork(BASE_URI, input.id));
    }

    @Test(expected = ConflictHttpException.class)
    public void testCreateConflict() throws Exception {

        doThrow(StatePathExistsException.class).when(plugin).createNetwork(
                any(Network.class));

        testObject.create(new Network());
    }

    @Test(expected = NotFoundHttpException.class)
    public void testGetNotFound() throws Exception {

        doReturn(null).when(plugin).getNetwork(any(UUID.class));

        testObject.get(UUID.randomUUID());
    }

    @Test
    public void testUpdate() throws Exception {

        Network input = network();
        Network output = network(input.id);

        doReturn(output).when(plugin).updateNetwork(input.id, input);

        Response resp = testObject.update(input.id, input);

        assertUpdate(resp, output);
    }

    @Test(expected = NotFoundHttpException.class)
    public void testUpdateNotFound() throws Exception {

        doThrow(NoStatePathException.class).when(plugin).updateNetwork(
                any(UUID.class), any(Network.class));

        testObject.update(any(UUID.class), any(Network.class));
    }
}
