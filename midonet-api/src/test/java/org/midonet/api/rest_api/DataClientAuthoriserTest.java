/*
 * Copyright 2015 Midokura SARL
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

package org.midonet.api.rest_api;

import java.security.Principal;
import java.util.UUID;

import javax.ws.rs.core.SecurityContext;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;

import org.midonet.api.auth.AuthRole;
import org.midonet.api.auth.ForbiddenHttpException;
import org.midonet.cluster.DataClient;
import org.midonet.cluster.data.Bridge;
import org.midonet.cluster.data.Router;
import org.midonet.cluster.data.ports.BridgePort;
import org.midonet.cluster.data.ports.RouterPort;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.doReturn;

@RunWith(MockitoJUnitRunner.class)
public class DataClientAuthoriserTest {

    @Mock(answer = Answers.RETURNS_SMART_NULLS)
    private DataClient dataClient;

    @Mock(answer = Answers.RETURNS_SMART_NULLS)
    private SecurityContext securityContext;

    private final String tenantId = "the-tenant-id";
    private final Bridge b = new Bridge(UUID.randomUUID());
    private final Router r = new Router(UUID.randomUUID());
    private final BridgePort bp = new BridgePort();
    private final RouterPort rp = new RouterPort();

    @Before
    public void setUp() throws Exception {
        r.setProperty(Router.Property.tenant_id, tenantId);
        b.setProperty(Bridge.Property.tenant_id, tenantId);

        bp.setId(UUID.randomUUID());
        bp.setDeviceId(b.getId());

        rp.setId(UUID.randomUUID());
        rp.setDeviceId(r.getId());

        doReturn(b).when(dataClient).bridgesGet(b.getId());
        doReturn(bp).when(dataClient).portsGet(bp.getId());

        doReturn(r).when(dataClient).routersGet(r.getId());
        doReturn(rp).when(dataClient).portsGet(rp.getId());
    }

    private Authoriser authoriser() {
        Principal principal = new Principal() {
            @Override public String getName() { return tenantId; }
        };
        doReturn(principal).when(securityContext).getUserPrincipal();
        return new DataClientAuthoriser(dataClient, securityContext);
    }

    @Test
    public void testIsAdmin() throws Exception {
        Authoriser authoriser = authoriser();

        doReturn(false).when(securityContext).isUserInRole(AuthRole.ADMIN);
        assertFalse(authoriser.isAdmin());

        doReturn(true).when(securityContext).isUserInRole(AuthRole.ADMIN);
        assertTrue(authoriser.isAdmin());

        // isAdminOrOwner is consistent
        assertTrue(authoriser.isAdminOrOwner("whatever"));
    }

    @Test
    public void testIsOwner() throws Exception {
        Authoriser authoriser = authoriser();
        assertTrue(authoriser.isOwner(tenantId));
        assertFalse(authoriser.isOwner("something-else"));

        // isAdminOrOwner is consistent
        assertTrue(authoriser.isAdminOrOwner(tenantId));
        assertFalse(authoriser.isAdminOrOwner("something-else"));
    }

    // AUTHORISE ROUTERS and their ports

    @Test
    public void testTryAuthoriseNonExistingRouter() throws Exception {
        Authoriser authoriser = authoriser();

        UUID someId = UUID.randomUUID();

        doReturn(null).when(dataClient).routersGet(someId);
        doReturn(null).when(dataClient).portsGet(someId);
        assertNull(authoriser.tryAuthoriseRouter(someId, anyString()));
        assertNull(authoriser.tryAuthorisePort(someId, anyString()));
    }

    @Test(expected = ForbiddenHttpException.class)
    public void testTryAuthoriseRouterOfAnotherTenant() throws Exception {
        Authoriser authoriser = authoriser();

        r.setProperty(Router.Property.tenant_id, "some-other-tenant");

        authoriser.tryAuthoriseRouter(r.getId(), anyString());
    }

    @Test(expected = ForbiddenHttpException.class)
    public void testTryAuthoriseRouterPortOfAnotherTenant() throws Exception {
        Authoriser authoriser = authoriser();

        r.setProperty(Router.Property.tenant_id, "some-other-tenant");

        authoriser.tryAuthorisePort(rp.getId(), anyString());
    }

    @Test
    public void testTryAuthoriseRouterAsAdminTenant() throws Exception {
        Authoriser authoriser = authoriser();

        doReturn(true).when(securityContext).isUserInRole(AuthRole.ADMIN);

        assertEquals(r, authoriser.tryAuthoriseRouter(r.getId(), anyString()));
        assertEquals(rp, authoriser.tryAuthorisePort(rp.getId(), anyString()));
    }

    @Test
    public void testTryAuthoriseRouterAsTenantAdmin() throws Exception {
        Authoriser authoriser = authoriser();

        doReturn(false).when(securityContext).isUserInRole(AuthRole.ADMIN);

        assertEquals(r, authoriser.tryAuthoriseRouter(r.getId(), anyString()));
        assertEquals(rp, authoriser.tryAuthorisePort(rp.getId(), anyString()));
    }

    // AUTHORISE BRIDGES and their ports

    @Test
    public void testTryAuthoriseNonExistingBridge() throws Exception {
        Authoriser authoriser = authoriser();

        UUID someId = UUID.randomUUID();

        doReturn(null).when(dataClient).bridgesGet(someId);
        doReturn(null).when(dataClient).portsGet(someId);
        assertNull(authoriser.tryAuthoriseBridge(someId, anyString()));
        assertNull(authoriser.tryAuthorisePort(someId, anyString()));
    }

    @Test(expected = ForbiddenHttpException.class)
    public void testTryAuthoriseBridgeOfAnotherTenant() throws Exception {
        Authoriser authoriser = authoriser();

        b.setProperty(Bridge.Property.tenant_id, "some-other-tenant");

        authoriser.tryAuthoriseBridge(b.getId(), anyString());
    }

    @Test(expected = ForbiddenHttpException.class)
    public void testTryAuthoriseBridgePortOfAnotherTenant() throws Exception {
        Authoriser authoriser = authoriser();

        b.setProperty(Bridge.Property.tenant_id, "some-other-tenant");

        authoriser.tryAuthorisePort(bp.getId(), anyString());
    }

    @Test
    public void testTryAuthoriseBridgeAsAdminTenant() throws Exception {
        Authoriser authoriser = authoriser();

        doReturn(true).when(securityContext).isUserInRole(AuthRole.ADMIN);

        assertEquals(b, authoriser.tryAuthoriseBridge(b.getId(), anyString()));
        assertEquals(bp, authoriser.tryAuthorisePort(bp.getId(), anyString()));
    }

    @Test
    public void testTryAuthoriseBridgeAsTenantAdmin() throws Exception {
        Authoriser authoriser = authoriser();

        doReturn(false).when(securityContext).isUserInRole(AuthRole.ADMIN);

        assertEquals(b, authoriser.tryAuthoriseBridge(b.getId(), anyString()));
        assertEquals(bp, authoriser.tryAuthorisePort(bp.getId(), anyString()));
    }












    public void testTryAuthorisePortGroup() throws Exception {

    }

    public void testTryAuthoriseChain() throws Exception {

    }

    public void testTryAuthoriseBgp() throws Exception {

    }

    public void testTryAuthoriseRule() throws Exception {

    }

    public void testTryAuthoriseAdRoute() throws Exception {

    }
}