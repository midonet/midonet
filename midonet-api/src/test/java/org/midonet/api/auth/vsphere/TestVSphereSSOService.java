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
package org.midonet.api.auth.vsphere;

import java.net.MalformedURLException;
import java.rmi.RemoteException;
import java.util.List;

import javax.servlet.http.HttpServletRequest;

import com.google.common.base.Optional;
import com.vmware.vim25.Permission;
import com.vmware.vim25.mo.Datacenter;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import org.midonet.cluster.auth.AuthException;
import org.midonet.cluster.auth.AuthRole;
import org.midonet.cluster.auth.Tenant;
import org.midonet.cluster.auth.Token;
import org.midonet.cluster.auth.UserIdentity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

public class TestVSphereSSOService {

    private VSphereSSOService vSphereSSOService;

    @Mock
    private VSphereClient mockVSphereClient;
    @Mock
    private VSphereConfig mockVSphereConfig;
    @Mock
    private VSphereServiceInstance mockVSphereServerInstance;
    @Mock
    private Datacenter mockDatacenter;
    @Mock
    private Permission mockPermission;
    @Mock
    private HttpServletRequest mockHttpServletRequest;

    private String sessionId = "1234-abcd-5678-efgh";
    private String username = "root";
    private String datacenterName = "datacenter-1";
    private int roleId = -1;

    @Before
    public void setUp() throws MalformedURLException, RemoteException {
        MockitoAnnotations.initMocks(this);

        when(mockVSphereConfig.getServiceDCId()).thenReturn(datacenterName);

        when(mockVSphereClient.loginWithCredentials("root", "root"))
                .thenReturn(mockVSphereServerInstance);
        when(mockVSphereClient.loginWithSessionCookie(sessionId))
                .thenReturn(mockVSphereServerInstance);

        when(mockVSphereServerInstance.isSessionActive()).thenReturn(true);
        when(mockVSphereServerInstance.getUserName())
                .thenReturn(Optional.of(username));
        when(mockVSphereServerInstance.getDatacenter(datacenterName))
                .thenReturn(Optional.of(mockDatacenter));
        when(mockVSphereServerInstance
                .retrieveEntityPermissions(mockDatacenter, true))
                .thenReturn(new Permission[]{mockPermission});

        when(mockPermission.getPrincipal()).thenReturn(username);
        when(mockPermission.getRoleId()).thenReturn(roleId);

        vSphereSSOService =
                new VSphereSSOService(mockVSphereClient, mockVSphereConfig);
    }

    @Test
    public void login() throws AuthException {
        String sessionId = "1234-abcd-5678-efgh";
        when(mockVSphereServerInstance.isSessionActive()).thenReturn(true);
        when(mockVSphereServerInstance.getSessionCookieId())
                .thenReturn(sessionId);

        Token token =
                vSphereSSOService.login("root", "root", mockHttpServletRequest);

        assertNotNull(token);
        assertEquals(sessionId, token.getKey());
    }

    @Test(expected=AuthException.class)
    public void loginNoSession() throws AuthException {
        when(mockVSphereServerInstance.isSessionActive()).thenReturn(false);
        vSphereSSOService.login("root", "root", (mockHttpServletRequest));
    }

    @Test
    public void getUserIdentityByTokenFullRights()
            throws AuthException, RemoteException {
        when(mockVSphereServerInstance.getPrivilegesForRole(roleId))
                .thenReturn(Optional.of(new String[]{
                        "Network.Config",
                        "Network.Delete",
                        "Network.Move",
                        "Network.Assign"
                }));

        UserIdentity userIdentity =
                vSphereSSOService.getUserIdentityByToken(sessionId);

        assertEquals("Datacenter Admin", userIdentity.getTenantId());
        assertEquals("Datacenter Admin", userIdentity.getTenantName());
        assertEquals(sessionId, userIdentity.getToken());
        assertEquals(username, userIdentity.getUserId());
        assertTrue(userIdentity.hasRole(AuthRole.ADMIN));
    }

    @Test(expected=VSphereAuthException.class)
    public void getUserIdentityByTokenIncompleteNetworkPermissions()
            throws RemoteException, AuthException {
        when(mockVSphereServerInstance.getPrivilegesForRole(roleId))
                .thenReturn(Optional.of(new String[]{
                        "Network.Config",
                        "Network.Delete",
                }));

        vSphereSSOService.getUserIdentityByToken(sessionId);
    }

    @Test(expected=VSphereAuthException.class)
    public void getUserIdentityByTokenNullToken()
            throws RemoteException, AuthException {

        vSphereSSOService.getUserIdentityByToken(null);
    }

    @Test(expected=VSphereAuthException.class)
    public void getUserIdentityByTokenEmptyToken()
            throws RemoteException, AuthException {

        vSphereSSOService.getUserIdentityByToken("");
    }

    @Test
    public void getTenants() throws AuthException {
        List<Tenant> tenants =
                vSphereSSOService.getTenants(mockHttpServletRequest);

        assertEquals(1, tenants.size());
        assertEquals("Datacenter Admin", tenants.get(0).getId());
    }

    @Test
    public void authenticateWithAdminToken() throws AuthException {
        String adminToken = "admin_token";

        when(mockVSphereConfig.getAdminToken()).thenReturn(adminToken);

        UserIdentity userIdentity =
                vSphereSSOService.getUserIdentityByToken(adminToken);

        assertTrue(userIdentity.hasRole(AuthRole.ADMIN));
        assertEquals("admin", userIdentity.getUserId());
        assertEquals(adminToken, userIdentity.getToken());
    }
}
