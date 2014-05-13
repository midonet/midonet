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
import org.midonet.cluster.data.neutron.Subnet;
import org.midonet.midolman.state.NoStatePathException;
import org.midonet.midolman.state.StatePathExistsException;
import org.mockito.runners.MockitoJUnitRunner;

import javax.ws.rs.core.Response;
import java.util.UUID;

import static org.mockito.Matchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;

@RunWith(MockitoJUnitRunner.class)
public class TestSubnetResource extends ResourceTest {

    private SubnetResource testObject;

    @Before
    public void setUp() throws Exception {

        super.setUp();

        testObject = new SubnetResource(config, uriInfo, context, plugin);
    }

    @Test
    public void testCreate() throws Exception {

        Subnet input = NeutronDataProvider.subnet();
        Subnet output = NeutronDataProvider.subnet(input.id);

        doReturn(output).when(plugin).createSubnet(input);

        Response resp = testObject.create(input);

        assertCreate(resp, output,
                NeutronUriBuilder.getSubnet(BASE_URI, input.id));
    }

    @Test(expected = ConflictHttpException.class)
    public void testCreateConflict() throws Exception {

        doThrow(StatePathExistsException.class).when(plugin).createSubnet(
                any(Subnet.class));

        testObject.create(new Subnet());
    }

    @Test(expected = NotFoundHttpException.class)
    public void testGetNotFound() throws Exception {

        doReturn(null).when(plugin).getSubnet(any(UUID.class));

        testObject.get(UUID.randomUUID());
    }

    @Test
    public void testUpdate() throws Exception {

        Subnet input = NeutronDataProvider.subnet();
        Subnet output = NeutronDataProvider.subnet(input.id);

        doReturn(output).when(plugin).updateSubnet(input.id, input);

        Response resp = testObject.update(input.id, input);

        assertUpdate(resp, output);
    }

    @Test(expected = NotFoundHttpException.class)
    public void testUpdateNotFound() throws Exception {

        doThrow(NoStatePathException.class).when(plugin).updateSubnet(
                any(UUID.class), any(Subnet.class));

        testObject.update(any(UUID.class), any(Subnet.class));
    }
}
