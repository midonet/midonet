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

import java.rmi.RemoteException;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import javax.servlet.http.HttpServletRequest;

import com.google.common.base.Objects;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.inject.Inject;
import com.vmware.vim25.Permission;
import com.vmware.vim25.mo.Datacenter;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.api.auth.MockAuthService;
import org.midonet.cluster.auth.AuthException;
import org.midonet.cluster.auth.AuthRole;
import org.midonet.cluster.auth.AuthService;
import org.midonet.cluster.auth.Tenant;
import org.midonet.cluster.auth.Token;
import org.midonet.cluster.auth.UserIdentity;

public class VSphereSSOService implements AuthService {

    private final static Logger log =
            LoggerFactory.getLogger(VSphereSSOService.class);

    private final String globalTenantId = "Datacenter Admin";
    private final Tenant globalTenant =
            new MockAuthService.MockTenant(globalTenantId);
    private final VSphereClient vSphereClient;
    private final VSphereConfig vSphereConfig;
    private final static Collection<String> networkPermissions =
            Arrays.asList(
                    "Network.Move",
                    "Network.Assign",
                    "Network.Delete",
                    "Network.Config"
            );

    @Inject
    public VSphereSSOService(VSphereClient vSphereClient,
                             VSphereConfig vSphereConfig) {
        this.vSphereClient = vSphereClient;
        this.vSphereConfig = vSphereConfig;

        log.debug("Using the following configuration: {}",
                Objects.toStringHelper(vSphereConfig)
                        .add("AdminToken", vSphereConfig.getAdminToken())
                        .add("Datacenter ID", vSphereConfig.getServiceDCId())
                        .add("Service URL", vSphereConfig.getServiceSdkUrl())
                        .add("Cert Fingerprint",
                                vSphereConfig.getServiceSSLCertFingerprint())
                        .add("Ignore server cert",
                                vSphereConfig.ignoreServerCert())
                        .toString());
    }

    @Override
    public UserIdentity getUserIdentityByToken(String token)
            throws AuthException {

        if(StringUtils.isEmpty(token)) {
            throw new VSphereAuthException("Authentication token is required");
        }

        // Authenticate via the ADMIN token
        if(token.equals(vSphereConfig.getAdminToken())) {
            return getUserIdentity("admin", token, AuthRole.ADMIN);
        }

        VSphereServiceInstance vSphereServiceInstance;
        try {
            vSphereServiceInstance = vSphereClient.loginWithSessionCookie(token);
        }
        catch (Exception e) {
            throw new VSphereAuthException("An exception occurred while logging " +
                    "in the vSphere server" + vSphereClient.getURL(), e);
        }

        if(!vSphereServiceInstance.isSessionActive()) {
            throw new VSphereAuthException("There's no active session for " +
                    "the cookie: " + token);
        }

        String username = vSphereServiceInstance.getUserName().get();

        String userRole;
        try {
            userRole = verifyRole(vSphereServiceInstance);
        }
        catch (RemoteException e) {
            throw new VSphereAuthException("An exception occurred while " +
                    "retrieving role for user " +  username, e);
        }

        return getUserIdentity(username, token, userRole);
    }

    @Override
    public Token login(String username, String password,
                       HttpServletRequest request) throws AuthException {

        VSphereServiceInstance vSphereServiceInstance;
        try {
            vSphereServiceInstance =
                    vSphereClient.loginWithCredentials(username, password);
        }
        catch (Exception e) {
            throw new VSphereAuthException(String.format("An exception " +
                    "occurred while login to the vSphere server %s with" +
                    "username %s", vSphereClient.getURL(), username), e);
        }

        if(!vSphereServiceInstance.isSessionActive()) {
            throw new VSphereAuthException("There's no active session " +
                    "for the user: " + username);
        }

        Token token = new Token();
        token.setKey(vSphereServiceInstance.getSessionCookieId());

        return token;
    }

    @Override
    public Tenant getTenant(String id) throws AuthException {
        return id.equals(globalTenantId) ? globalTenant : null;
    }

    /**
     * We consider the vSphere environment a single tenant env.
     * A single, global tenant will be taken into account
     */
    @Override
    public List<Tenant> getTenants(HttpServletRequest request)
            throws AuthException {
        return ImmutableList.of(globalTenant);
    }

    /**
     * Determine the Midonet role for the logged user.
     * There's only one role allowed: {code AuthRole.ADMIN}
     * The role is assigned iff the user has all the vSphere Network.*
     * privileges assigned or inherited on the datacenter object specified in
     * the configuration
     * @param vSphereServiceInstance
     *      The current session
     * @return
     *      The role associated to the user
     * @throws RemoteException
     *      If an exception occur while exchanging data with the remote server
     * @throws VSphereAuthException
     *      If the logged user cannot gain the ADMIN role
     */
    private String verifyRole(VSphereServiceInstance vSphereServiceInstance)
            throws RemoteException, VSphereAuthException {

        if(!vSphereServiceInstance.isSessionActive()) {
            throw new VSphereAuthException("No active session found in the " +
                    "serviceInstance: " + vSphereClient.toString());
        }

        String datacenterName = vSphereConfig.getServiceDCId();
        Optional<Datacenter> datacenter =
                vSphereServiceInstance.getDatacenter(datacenterName);

        if(!datacenter.isPresent()) {
            throw new VSphereAuthException("No datacenter found: "
                    + datacenterName);
        }

        String username = vSphereServiceInstance.getUserName().get();
        Permission [] permissions =
                vSphereServiceInstance.retrieveEntityPermissions(
                        datacenter.get(), true);

        Integer roleId = null;
        for(Permission permission: permissions) {
            if(permission.getPrincipal().equals(username)) {
                roleId = permission.getRoleId();
                break;
            }
        }

        if(roleId == null) {
            throw new VSphereAuthException(
                    String.format("No roles found for user %s on datacenter %s",
                            username, datacenterName));
        }

        Optional<String[]> privileges =
                vSphereServiceInstance.getPrivilegesForRole(roleId);

        if(!privileges.isPresent()) {
            throw new VSphereAuthException("No privileges found for role "
                    + roleId);
        }

        if(Arrays.asList(privileges.get()).containsAll(networkPermissions)) {
            return AuthRole.ADMIN;
        }

        throw new VSphereAuthException(String.format("User %s doesn't have " +
                "network privileges on %s", username, datacenterName));
    }

    private UserIdentity getUserIdentity(String username, String token,
                                         String userRole) {
        UserIdentity userIdentity = new UserIdentity();

        userIdentity.setTenantId(globalTenantId);
        userIdentity.setTenantName(globalTenantId);
        userIdentity.setToken(token);
        userIdentity.setUserId(username);
        userIdentity.addRole(userRole);

        return userIdentity;
    }

    @Override
    public String toString() {
        return Objects.toStringHelper(this)
                .add("VSphereClient", vSphereClient)
                .add("VSphereConfig", vSphereConfig)
                .add("TenantID", globalTenantId)
                .toString();
    }
}
