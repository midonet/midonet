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
import java.net.URL;
import java.rmi.RemoteException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.base.Optional;
import com.jcabi.aspects.RetryOnFailure;
import com.vmware.vim25.AuthorizationRole;
import com.vmware.vim25.Permission;
import com.vmware.vim25.mo.Datacenter;
import com.vmware.vim25.mo.InventoryNavigator;
import com.vmware.vim25.mo.ManagedEntity;
import com.vmware.vim25.mo.ServiceInstance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A wrapper around the {@code ServiceInstance} object
 */
public class VSphereServiceInstance {

    private final static Logger log =
            LoggerFactory.getLogger(VSphereServiceInstance.class);

    private static final Pattern SOAP_SESSION_COOKIE_REGEX_PATTERN =
            Pattern.compile("vmware_soap_session=\"([0-9a-zA-Z-]*)");
    private final ServiceInstance serviceInstance;

    @VisibleForTesting VSphereServiceInstance(ServiceInstance serviceInstance) {
        this.serviceInstance = serviceInstance;
    }

    @RetryOnFailure(attempts=3, types=RemoteException.class)
    public static VSphereServiceInstance forCredentials(URL sdkUrl,
                                                        String username,
                                                        String password,
                                                        boolean ignoreCerts)
            throws MalformedURLException, RemoteException {
        return new VSphereServiceInstance(
                new ServiceInstance(sdkUrl, username, password, ignoreCerts)
        );
    }

    @RetryOnFailure(attempts=3, types=RemoteException.class)
    public static VSphereServiceInstance forSessionCookie(URL sdkUrl,
                                                          String soapSessionId,
                                                          boolean ignoreCerts)
            throws MalformedURLException, RemoteException {
        return new VSphereServiceInstance(
                new ServiceInstance(
                        sdkUrl,
                        String.format("vmware_soap_session=\"%s\"",
                                soapSessionId), ignoreCerts)
        );
    }

    public String getSessionCookieId() throws VSphereAuthException {
        return getSessionCookieId(serviceInstance.getServerConnection()
                .getVimService().getWsc().getCookie());
    }

    @VisibleForTesting String getSessionCookieId(String cookie)
            throws VSphereAuthException {

          /* A cookie is something like:
            "vmware_soap_session="52f8c71a-6737-51a1-886e-7863759142a6"; \
            Path=/; HttpOnly; Secure;"
        */
        Matcher matcher = SOAP_SESSION_COOKIE_REGEX_PATTERN.matcher(cookie);

        if(matcher.find()) {
            return matcher.group(1);
        }

        log.debug("Cannot find a valid soap session cookie in " + cookie);
        throw new VSphereAuthException("Soap session cookie not found");
    }

    /**
     * This call actually validates that session is active with the server
     */
    public boolean isSessionActive() {
        return serviceInstance.getSessionManager().getCurrentSession() != null;
    }

    public Optional<String> getUserName() {
        if(isSessionActive()) {
            return Optional.of(
                    serviceInstance.getSessionManager().getCurrentSession()
                            .getUserName());
        }

        return Optional.absent();
    }


    public Optional<Datacenter> getDatacenter(String datacenterId)
            throws RemoteException {
        return getDatacenter(datacenterId,
                new InventoryNavigator(serviceInstance.getRootFolder()));
    }

    @RetryOnFailure(attempts=3, types=RemoteException.class)
    @VisibleForTesting Optional<Datacenter> getDatacenter(String datacenterId,
                                               InventoryNavigator inventory)
            throws RemoteException {
        ManagedEntity[] datacenters =
                inventory.searchManagedEntities("Datacenter");
        for (ManagedEntity managedDC: datacenters) {
            Datacenter datacenter = (Datacenter) managedDC;
            if(datacenter.getMOR().getVal().equals(datacenterId)) {
                return Optional.of(datacenter);
            }
        }

        return Optional.absent();
    }

    @RetryOnFailure(attempts=3, types=RemoteException.class)
    public Permission[] retrieveEntityPermissions(ManagedEntity entity,
                                                  boolean inherited)
            throws RemoteException {
        if(isSessionActive()) {
            return serviceInstance.getAuthorizationManager()
                    .retrieveEntityPermissions(entity, inherited);
        }

        return new Permission[] {};
    }

    @RetryOnFailure(attempts=3, types=RemoteException.class)
    public Optional<String[]> getPrivilegesForRole(int roleId)
            throws RemoteException {
        // WARN: possible performance hit here! A role list cache would help
        AuthorizationRole [] roles = serviceInstance
                .getAuthorizationManager().getRoleList();
        for(AuthorizationRole role: roles) {
            if (role.getRoleId() == roleId) {
                return Optional.of(role.getPrivilege());
            }
        }

        return Optional.absent();
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("ServiceInstance", serviceInstance)
                .add("isSessionActive", isSessionActive())
                .add("Username", getUserName())
                .toString();
    }
}
