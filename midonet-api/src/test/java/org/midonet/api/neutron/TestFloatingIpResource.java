/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.api.neutron;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.midonet.api.ResourceTest;
import org.midonet.api.rest_api.ConflictHttpException;
import org.midonet.api.rest_api.NotFoundHttpException;
import org.midonet.cluster.data.neutron.FloatingIp;
import org.midonet.midolman.state.NoStatePathException;
import org.midonet.midolman.state.StatePathExistsException;
import org.mockito.runners.MockitoJUnitRunner;

import javax.ws.rs.core.Response;
import java.util.UUID;

import static org.mockito.Matchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;

@RunWith(MockitoJUnitRunner.class)
public class TestFloatingIpResource extends ResourceTest {

    private FloatingIpResource testObject;

    public static FloatingIp floatingIp() {
        return floatingIp(UUID.randomUUID());
    }

    public static FloatingIp floatingIp(UUID id) {
        FloatingIp r= new FloatingIp();
        r.id = id;
        return r;
    }

    @Before
    public void setUp() throws Exception {

        super.setUp();

        testObject = new FloatingIpResource(config, uriInfo, context, plugin);
    }

    @Test
    public void testCreate() throws Exception {

        FloatingIp input = floatingIp();
        FloatingIp output = floatingIp(input.id);

        doReturn(output).when(plugin).createFloatingIp(input);

        Response resp = testObject.create(input);

        assertCreate(resp, output,
                NeutronUriBuilder.getFloatingIp(BASE_URI, input.id));
    }

    @Test(expected = ConflictHttpException.class)
    public void testCreateConflict() throws Exception {

        doThrow(StatePathExistsException.class).when(plugin).createFloatingIp(
                any(FloatingIp.class));

        testObject.create(new FloatingIp());
    }

    @Test(expected = NotFoundHttpException.class)
    public void testGetNotFound() throws Exception {

        doReturn(null).when(plugin).getFloatingIp(any(UUID.class));

        testObject.get(UUID.randomUUID());
    }

    @Test
    public void testUpdate() throws Exception {

        FloatingIp input = floatingIp();
        FloatingIp output = floatingIp(input.id);

        doReturn(output).when(plugin).updateFloatingIp(input.id, input);

        Response resp = testObject.update(input.id, input);

        assertUpdate(resp, output);
    }

    @Test(expected = NotFoundHttpException.class)
    public void testUpdateNotFound() throws Exception {

        doThrow(NoStatePathException.class).when(plugin).updateFloatingIp(
                any(UUID.class), any(FloatingIp.class));

        testObject.update(any(UUID.class), any(FloatingIp.class));
    }
}
