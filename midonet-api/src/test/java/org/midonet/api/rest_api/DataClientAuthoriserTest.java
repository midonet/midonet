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

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;

import org.midonet.cluster.DataClient;
import org.midonet.cluster.auth.AuthRole;
import org.midonet.cluster.data.AdRoute;
import org.midonet.cluster.data.BGP;
import org.midonet.cluster.data.Bridge;
import org.midonet.cluster.data.Chain;
import org.midonet.cluster.data.PortGroup;
import org.midonet.cluster.data.Router;
import org.midonet.cluster.data.ports.BridgePort;
import org.midonet.cluster.data.ports.RouterPort;
import org.midonet.cluster.data.rules.JumpRule;
import org.midonet.cluster.data.rules.LiteralRule;
import org.midonet.cluster.rest_api.ForbiddenHttpException;
import org.midonet.midolman.rules.Condition;
import org.midonet.midolman.rules.RuleResult;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
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
    private final Chain c = new Chain(UUID.randomUUID());
    private final PortGroup pg = new PortGroup();
    private final BridgePort bp = new BridgePort();
    private final RouterPort rp = new RouterPort();
    private final BGP bgp = new BGP();
    private final AdRoute adRoute = new AdRoute();

    private final JumpRule jRule = new JumpRule(UUID.randomUUID(),
                                                new Condition(),
                                                new JumpRule.Data());

    private final LiteralRule nonJRule = new LiteralRule(UUID.randomUUID(),
                                                     new Condition(),
                                                     RuleResult.Action.ACCEPT,
                                                     new LiteralRule.Data());

    @Before
    public void setUp() throws Exception {
        r.setProperty(Router.Property.tenant_id, tenantId);
        b.setProperty(Bridge.Property.tenant_id, tenantId);
        pg.setProperty(PortGroup.Property.tenant_id, tenantId);
        c.setProperty(Chain.Property.tenant_id, tenantId);

        pg.setId(UUID.randomUUID());

        bp.setId(UUID.randomUUID());
        bp.setDeviceId(b.getId());

        rp.setId(UUID.randomUUID());
        rp.setDeviceId(r.getId());

        bgp.setId(UUID.randomUUID());
        bgp.setPortId(rp.getId());

        adRoute.setId(UUID.randomUUID());
        adRoute.setBgpId(bgp.getId());

        jRule.setChainId(c.getId());
        nonJRule.setChainId(c.getId());
        jRule.setJumpToChainId(null);

        doReturn(bgp).when(dataClient).bgpGet(bgp.getId());

        doReturn(b).when(dataClient).bridgesGet(b.getId());
        doReturn(bp).when(dataClient).portsGet(bp.getId());

        doReturn(r).when(dataClient).routersGet(r.getId());
        doReturn(rp).when(dataClient).portsGet(rp.getId());

        doReturn(pg).when(dataClient).portGroupsGet(pg.getId());

        doReturn(c).when(dataClient).chainsGet(c.getId());

        doReturn(adRoute).when(dataClient).adRoutesGet(adRoute.getId());

        doReturn(jRule).when(dataClient).rulesGet(jRule.getId());
        doReturn(nonJRule).when(dataClient).rulesGet(nonJRule.getId());
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

    // AUTHORISE PORT GROUPS

    @Test
    public void testTryAuthoriseNonExistingPortGroup() throws Exception {
        Authoriser authoriser = authoriser();

        UUID someId = UUID.randomUUID();

        doReturn(null).when(dataClient).portGroupsGet(someId);
        assertNull(authoriser.tryAuthorisePortGroup(someId, anyString()));
    }

    @Test(expected = ForbiddenHttpException.class)
    public void testTryAuthorisePortGroupOfAnotherTenant() throws Exception {
        Authoriser authoriser = authoriser();

        pg.setProperty(PortGroup.Property.tenant_id, "some-other-tenant");

        authoriser.tryAuthorisePortGroup(pg.getId(), anyString());
    }

    @Test
    public void testTryAuthorisePortGroupAsAdminTenant() throws Exception {
        Authoriser authoriser = authoriser();

        doReturn(true).when(securityContext).isUserInRole(AuthRole.ADMIN);

        assertEquals(pg, authoriser.tryAuthorisePortGroup(pg.getId(),
                                                          anyString()));
    }

    @Test
    public void testTryAuthorisePortGroupAsTenantAdmin() throws Exception {
        Authoriser authoriser = authoriser();

        doReturn(false).when(securityContext).isUserInRole(AuthRole.ADMIN);

        assertEquals(pg, authoriser.tryAuthorisePortGroup(pg.getId(),
                                                          anyString()));
    }


    // AUTHORISE CHAINS

    @Test
    public void testTryAuthoriseNonExistingChain() throws Exception {
        Authoriser authoriser = authoriser();

        UUID someId = UUID.randomUUID();

        doReturn(null).when(dataClient).chainsGet(someId);
        assertNull(authoriser.tryAuthoriseChain(someId, anyString()));
    }

    @Test(expected = ForbiddenHttpException.class)
    public void testTryAuthoriseChainOfAnotherTenant() throws Exception {
        Authoriser authoriser = authoriser();

        c.setProperty(Chain.Property.tenant_id, "some-other-tenant");

        authoriser.tryAuthoriseChain(c.getId(), anyString());
    }

    @Test
    public void testTryAuthoriseChainAsAdminTenant() throws Exception {
        Authoriser authoriser = authoriser();

        doReturn(true).when(securityContext).isUserInRole(AuthRole.ADMIN);

        assertEquals(c, authoriser.tryAuthoriseChain(c.getId(), anyString()));
    }

    @Test
    public void testTryAuthoriseChainAsTenantAdmin() throws Exception {
        Authoriser authoriser = authoriser();

        doReturn(false).when(securityContext).isUserInRole(AuthRole.ADMIN);

        assertEquals(c, authoriser.tryAuthoriseChain(c.getId(), anyString()));
    }


    // AUTHORISE BGP

    @Test
    public void testTryAuthoriseNonExistingBGP() throws Exception {
        Authoriser authoriser = authoriser();

        UUID someId = UUID.randomUUID();

        doReturn(null).when(dataClient).bgpGet(someId);
        assertNull(authoriser.tryAuthoriseBgp(someId, anyString()));
    }

    @Test(expected = ForbiddenHttpException.class)
    public void testTryAuthoriseBgpOfAnotherTenant() throws Exception {
        Authoriser authoriser = authoriser();

        // Alter the associated router
        r.setProperty(Router.Property.tenant_id, "some-other-tenant");

        authoriser.tryAuthoriseBgp(bgp.getId(), anyString());
    }

    @Test
    public void testTryAuthoriseBgpAsAdminTenant() throws Exception {
        Authoriser authoriser = authoriser();

        doReturn(true).when(securityContext).isUserInRole(AuthRole.ADMIN);

        assertEquals(bgp, authoriser.tryAuthoriseBgp(bgp.getId(),
                                                     anyString()));
    }

    @Test
    public void testTryAuthoriseBgpAsTenantAdmin() throws Exception {
        Authoriser authoriser = authoriser();

        doReturn(false).when(securityContext).isUserInRole(AuthRole.ADMIN);

        assertEquals(bgp, authoriser.tryAuthoriseBgp(bgp.getId(), anyString()));
    }


    // AUTHORISE AD ROUTE

    @Test
    public void testTryAuthoriseNonExistingAdRoute() throws Exception {
        Authoriser authoriser = authoriser();

        UUID someId = UUID.randomUUID();

        doReturn(null).when(dataClient).adRoutesGet(someId);
        assertNull(authoriser.tryAuthoriseAdRoute(someId, anyString()));
    }

    @Test(expected = ForbiddenHttpException.class)
    public void testTryAuthoriseAdRouteOfAnotherTenant() throws Exception {
        Authoriser authoriser = authoriser();

        // Alter the associated router
        r.setProperty(Router.Property.tenant_id, "some-other-tenant");

        authoriser.tryAuthoriseAdRoute(adRoute.getId(), anyString());
    }

    @Test
    public void testTryAuthoriseAdRouteAsAdminTenant() throws Exception {
        Authoriser authoriser = authoriser();

        doReturn(true).when(securityContext).isUserInRole(AuthRole.ADMIN);

        assertEquals(adRoute, authoriser.tryAuthoriseAdRoute(adRoute.getId(),
                                                             anyString()));
    }

    @Test
    public void testTryAuthoriseAdRouteAsTenantAdmin() throws Exception {
        Authoriser authoriser = authoriser();

        doReturn(false).when(securityContext).isUserInRole(AuthRole.ADMIN);

        assertEquals(adRoute,
                     authoriser.tryAuthoriseAdRoute(adRoute.getId(),
                                                    anyString()));
    }


    // RULE AUTHORISATION

    @Test
    public void testTryAuthoriseNonExistingRule() throws Exception {
        Authoriser authoriser = authoriser();

        UUID someId = UUID.randomUUID();

        doReturn(null).when(dataClient).rulesGet(someId);
        assertNull(authoriser.tryAuthoriseRule(someId, anyString()));
    }

    @Test(expected = ForbiddenHttpException.class)
    public void testTryAuthoriseRuleOfNoTenant() throws Exception {
        Authoriser authoriser = authoriser();

        // Alter the associated chain to no tenant
        c.setProperty(Chain.Property.tenant_id, null);

        authoriser.tryAuthoriseRule(jRule.getId(), anyString());
        authoriser.tryAuthoriseRule(nonJRule.getId(), anyString());
    }

    @Test(expected = ForbiddenHttpException.class)
    public void testTryAuthoriseRuleOfAnotherTenant() throws Exception {
        Authoriser authoriser = authoriser();

        // Alter the associated chain to no tenant
        c.setProperty(Chain.Property.tenant_id, "some-other-tenant");

        try {
            authoriser.tryAuthoriseRule(jRule.getId(), anyString());
            Assert.fail("Expected " + ForbiddenHttpException.class);
        } catch (ForbiddenHttpException e) {
            // ok
        }

        authoriser.tryAuthoriseRule(nonJRule.getId(), anyString());
    }

    @Test
    public void testTryAuthoriseRuleAsAdminTenant() throws Exception {
        Authoriser authoriser = authoriser();

        doReturn(true).when(securityContext).isUserInRole(AuthRole.ADMIN);

        assertEquals(jRule, authoriser.tryAuthoriseRule(jRule.getId(),
                                                        anyString()));
        assertEquals(nonJRule, authoriser.tryAuthoriseRule(nonJRule.getId(),
                                                           anyString()));
    }

    @Test
    public void testTryAuthoriseRuleAsTenantAdmin() throws Exception {
        Authoriser authoriser = authoriser();

        doReturn(false).when(securityContext).isUserInRole(AuthRole.ADMIN);

        assertEquals(jRule,
                     authoriser.tryAuthoriseRule(jRule.getId(), anyString()));
        assertEquals(nonJRule,
                     authoriser.tryAuthoriseRule(nonJRule.getId(), anyString()));
    }

    @Test(expected = ForbiddenHttpException.class)
    public void testTryAuthoriseRuleWhenJumpHasNonExistingTarget()
        throws Exception {

        Authoriser authoriser = authoriser();

        doReturn(false).when(securityContext).isUserInRole(AuthRole.ADMIN);

        UUID someId = UUID.randomUUID();
        doReturn(null).when(dataClient).chainsGet(someId);
        jRule.setJumpToChainId(someId);

        assertEquals(nonJRule,
                     authoriser.tryAuthoriseRule(nonJRule.getId(), anyString()));

        // must throw
        authoriser.tryAuthoriseRule(jRule.getId(), anyString());
    }

    @Test(expected = ForbiddenHttpException.class)
    public void testTryAuthoriseRuleWhenChainHasNoTenant()
        throws Exception {

        Authoriser authoriser = authoriser();

        doReturn(false).when(securityContext).isUserInRole(AuthRole.ADMIN);

        Chain jumpChain = new Chain(UUID.randomUUID());
        jumpChain.setProperty(Chain.Property.tenant_id, null);
        jRule.setJumpToChainId(jumpChain.getId());

        doReturn(jumpChain).when(dataClient).chainsGet(jumpChain.getId());

        assertEquals(nonJRule,
                     authoriser.tryAuthoriseRule(nonJRule.getId(), anyString()));

        // must throw
        authoriser.tryAuthoriseRule(jRule.getId(), anyString());
    }

    @Test(expected = ForbiddenHttpException.class)
    public void testTryAuthoriseRuleWhenJumpHasTargetOfOtherTenant()
        throws Exception {

        Authoriser authoriser = authoriser();

        doReturn(false).when(securityContext).isUserInRole(AuthRole.ADMIN);

        Chain jumpChain = new Chain(UUID.randomUUID());
        jumpChain.setProperty(Chain.Property.tenant_id, "some-other-tenant");
        jRule.setJumpToChainId(jumpChain.getId());

        doReturn(jumpChain).when(dataClient).chainsGet(jumpChain.getId());

        assertEquals(nonJRule,
                     authoriser.tryAuthoriseRule(nonJRule.getId(), anyString()));

        // must throw
        authoriser.tryAuthoriseRule(jRule.getId(), anyString());
    }

    public void testTryAuthoriseRuleWhenJumpHasTargetOfSameTenant()
        throws Exception {

        Authoriser authoriser = authoriser();

        doReturn(false).when(securityContext).isUserInRole(AuthRole.ADMIN);

        Chain jumpChain = new Chain(UUID.randomUUID());
        jumpChain.setProperty(Chain.Property.tenant_id, tenantId);
        jRule.setJumpToChainId(jumpChain.getId());

        doReturn(jumpChain).when(dataClient).chainsGet(jumpChain.getId());

        assertEquals(nonJRule,
                     authoriser.tryAuthoriseRule(nonJRule.getId(), anyString()));

        assertEquals(jRule,
                     authoriser.tryAuthoriseRule(jRule.getId(), anyString()));
    }
}