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

import java.util.UUID;

import javax.ws.rs.core.Response;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.runners.MockitoJUnitRunner;

import org.midonet.api.ResourceTest;
import org.midonet.cluster.rest_api.ConflictHttpException;
import org.midonet.cluster.rest_api.NotFoundHttpException;
import org.midonet.cluster.rest_api.neutron.NeutronUriBuilder;
import org.midonet.cluster.rest_api.neutron.models.SecurityGroupRule;
import org.midonet.cluster.rest_api.neutron.resources.SecurityGroupRuleResource;

import static org.mockito.Matchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;

@RunWith(MockitoJUnitRunner.class)
public class TestSecurityGroupRuleResource extends ResourceTest {

    private SecurityGroupRuleResource testObject;

    public static SecurityGroupRule securityGroupRule() {
        return securityGroupRule(UUID.randomUUID());
    }

    public static SecurityGroupRule securityGroupRule(UUID id) {
        SecurityGroupRule rule = new SecurityGroupRule();
        rule.id = id;
        return rule;
    }

    @Before
    public void setUp() throws Exception {

        super.setUp();

        testObject = new SecurityGroupRuleResource(uriInfo, plugin);
    }

    @Test
    public void testCreate() throws Exception {

        SecurityGroupRule input = securityGroupRule();
        SecurityGroupRule output = securityGroupRule(
                input.id);

        doReturn(output).when(plugin).createSecurityGroupRule(input);

        Response resp = testObject.create(input);

        assertCreate(resp, output,
                NeutronUriBuilder.getSecurityGroupRule(BASE_URI, input.id));
    }

    @Test(expected = ConflictHttpException.class)
    public void testCreateConflict() throws Exception {

        doThrow(ConflictHttpException.class).when(
            plugin).createSecurityGroupRule(any(SecurityGroupRule.class));

        testObject.create(new SecurityGroupRule());
    }

    @Test(expected = NotFoundHttpException.class)
    public void testGetNotFound() throws Exception {

        doReturn(null).when(plugin).getSecurityGroupRule(any(UUID.class));

        testObject.get(UUID.randomUUID());
    }
}
