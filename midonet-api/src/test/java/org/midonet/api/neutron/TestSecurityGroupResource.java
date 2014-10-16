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
package org.midonet.api.neutron;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.midonet.api.ResourceTest;
import org.midonet.api.rest_api.ConflictHttpException;
import org.midonet.api.rest_api.NotFoundHttpException;
import org.midonet.cluster.data.neutron.SecurityGroup;
import org.midonet.midolman.state.NoStatePathException;
import org.midonet.midolman.state.StatePathExistsException;
import org.mockito.runners.MockitoJUnitRunner;

import javax.ws.rs.core.Response;
import java.util.UUID;

import static org.mockito.Matchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;

@RunWith(MockitoJUnitRunner.class)
public class TestSecurityGroupResource extends ResourceTest {

    private SecurityGroupResource testObject;

    public static SecurityGroup securityGroup() {
        return securityGroup(UUID.randomUUID());
    }

    public static SecurityGroup securityGroup(UUID id) {
        SecurityGroup sg = new SecurityGroup();
        sg.id = id;
        return sg;
    }

    @Before
    public void setUp() throws Exception {

        super.setUp();

        testObject = new SecurityGroupResource(config, uriInfo, context, plugin);
    }

    @Test
    public void testCreate() throws Exception {

        SecurityGroup input = securityGroup();
        SecurityGroup output = securityGroup(input.id);

        doReturn(output).when(plugin).createSecurityGroup(input);

        Response resp = testObject.create(input);

        assertCreate(resp, output,
                NeutronUriBuilder.getSecurityGroup(BASE_URI, input.id));
    }

    @Test(expected = ConflictHttpException.class)
    public void testCreateConflict() throws Exception {

        doThrow(StatePathExistsException.class).when(plugin).createSecurityGroup(
                any(SecurityGroup.class));

        testObject.create(new SecurityGroup());
    }

    @Test(expected = NotFoundHttpException.class)
    public void testGetNotFound() throws Exception {

        doReturn(null).when(plugin).getSecurityGroup(any(UUID.class));

        testObject.get(UUID.randomUUID());
    }

    @Test
    public void testUpdate() throws Exception {

        SecurityGroup input = securityGroup();
        SecurityGroup output = securityGroup(input.id);

        doReturn(output).when(plugin).updateSecurityGroup(input.id, input);

        Response resp = testObject.update(input.id, input);

        assertUpdate(resp, output);
    }

    @Test(expected = NotFoundHttpException.class)
    public void testUpdateNotFound() throws Exception {

        doThrow(NoStatePathException.class).when(plugin).updateSecurityGroup(
                any(UUID.class), any(SecurityGroup.class));

        testObject.update(any(UUID.class), any(SecurityGroup.class));
    }
}
