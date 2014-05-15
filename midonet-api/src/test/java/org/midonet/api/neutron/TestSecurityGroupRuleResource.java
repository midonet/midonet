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
import org.midonet.cluster.data.neutron.SecurityGroupRule;
import org.midonet.midolman.state.StatePathExistsException;
import org.mockito.runners.MockitoJUnitRunner;

import javax.ws.rs.core.Response;
import java.util.UUID;

import static org.mockito.Matchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;

@RunWith(MockitoJUnitRunner.class)
public class TestSecurityGroupRuleResource extends ResourceTest {

    private SecurityGroupRuleResource testObject;

    @Before
    public void setUp() throws Exception {

        super.setUp();

        testObject = new SecurityGroupRuleResource(config, uriInfo, context,
                plugin);
    }

    @Test
    public void testCreate() throws Exception {

        SecurityGroupRule input = NeutronDataProvider.securityGroupRule();
        SecurityGroupRule output = NeutronDataProvider.securityGroupRule(
                input.id);

        doReturn(output).when(plugin).createSecurityGroupRule(input);

        Response resp = testObject.create(input);

        assertCreate(resp, output,
                NeutronUriBuilder.getSecurityGroupRule(BASE_URI, input.id));
    }

    @Test(expected = ConflictHttpException.class)
    public void testCreateConflict() throws Exception {

        doThrow(StatePathExistsException.class).when(
                plugin).createSecurityGroupRule(any(SecurityGroupRule.class));

        testObject.create(new SecurityGroupRule());
    }

    @Test(expected = NotFoundHttpException.class)
    public void testGetNotFound() throws Exception {

        doReturn(null).when(plugin).getSecurityGroupRule(any(UUID.class));

        testObject.get(UUID.randomUUID());
    }
}
