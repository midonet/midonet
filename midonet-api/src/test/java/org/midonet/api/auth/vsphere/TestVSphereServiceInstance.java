/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.api.auth.vsphere;

import java.net.MalformedURLException;
import java.rmi.RemoteException;

import com.google.common.base.Optional;
import com.vmware.vim25.mo.Datacenter;
import com.vmware.vim25.mo.InventoryNavigator;
import com.vmware.vim25.mo.ManagedEntity;
import com.vmware.vim25.mo.ServiceInstance;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.*;

public class TestVSphereServiceInstance {

    private ServiceInstance mockServiceInstance;
    private VSphereServiceInstance vSphereServiceInstance;

    @Before
    public void setUp() throws MalformedURLException {
        mockServiceInstance = mock(ServiceInstance.class, RETURNS_DEEP_STUBS);
        vSphereServiceInstance = new VSphereServiceInstance(mockServiceInstance);
    }

    @Test
    public void getSessionCookieId() throws VSphereAuthException {
        String sessionCookie =
                "vmware_soap_session=\"52f8c71a-6737-51a1-886e-7863759142a6\";" +
                        " Path=/; HttpOnly; Secure;";
        String sessionCookieId =
                vSphereServiceInstance.getSessionCookieId(sessionCookie);

        assertEquals("52f8c71a-6737-51a1-886e-7863759142a6", sessionCookieId);
    }

    @Test
    public void getSessionCookieIdUpperCase() throws VSphereAuthException {
        String sessionCookie =
                "vmware_soap_session=\"52F8C71A-6737-51A1-886E-7863759142A6\";" +
                        " Path=/; HttpOnly; Secure;";
        String sessionCookieId =
                vSphereServiceInstance.getSessionCookieId(sessionCookie);

        assertEquals("52F8C71A-6737-51A1-886E-7863759142A6", sessionCookieId);
    }

    @Test(expected=VSphereAuthException.class)
    public void noSessionCookie() throws VSphereAuthException {
        String sessionCookie = "Path=/; HttpOnly; Secure;";
        vSphereServiceInstance.getSessionCookieId(sessionCookie);
    }

    @Test
    public void getUserName() {
        when(mockServiceInstance.getSessionManager()
                .getCurrentSession().getUserName()).thenReturn("admin");

        assertEquals(Optional.of("admin"),
                vSphereServiceInstance.getUserName());
    }

    @Test
    public void getUserNameNoSession() {
        when(mockServiceInstance.getSessionManager().getCurrentSession())
                .thenReturn(null);

        assertEquals(Optional.<String>absent(),
                vSphereServiceInstance.getUserName());
    }

    @Test
    public void getDatacenterNoDCFound() throws RemoteException {
        InventoryNavigator mockInventoryNavigator =
                mock(InventoryNavigator.class);
        Datacenter dc1 = mock(Datacenter.class, RETURNS_DEEP_STUBS);
        Datacenter dc2 = mock(Datacenter.class, RETURNS_DEEP_STUBS);

        when(dc1.getMOR().getVal()).thenReturn("dc1");
        when(dc2.getMOR().getVal()).thenReturn("dc2");
        when(mockInventoryNavigator.searchManagedEntities("Datacenter"))
                .thenReturn(new ManagedEntity[] {
            dc1, dc2
        });

        assertEquals(Optional.<Datacenter>absent(),
                vSphereServiceInstance.getDatacenter("mydatacenter",
                        mockInventoryNavigator));
    }

    @Test
    public void getDatacenterNoDCFoundEmptyList() throws RemoteException {
        InventoryNavigator mockInventoryNavigator =
                mock(InventoryNavigator.class);

        when(mockInventoryNavigator.searchManagedEntities("Datacenter"))
                .thenReturn(new ManagedEntity[] {});

        assertEquals(Optional.<Datacenter>absent(),
                vSphereServiceInstance.getDatacenter("mydatacenter",
                        mockInventoryNavigator));
    }

    @Test
    public void getDatacenter() throws RemoteException {
        InventoryNavigator mockInventoryNavigator =
                mock(InventoryNavigator.class);
        Datacenter dc1 = mock(Datacenter.class, RETURNS_DEEP_STUBS);
        Datacenter mydatacenter = mock(Datacenter.class, RETURNS_DEEP_STUBS);

        when(dc1.getMOR().getVal()).thenReturn("dc1");
        when(mydatacenter.getMOR().getVal()).thenReturn("mydatacenter");
        when(mockInventoryNavigator.searchManagedEntities("Datacenter"))
                .thenReturn(new ManagedEntity[] {
                dc1, mydatacenter
        });

        assertEquals(Optional.of(mydatacenter), vSphereServiceInstance
                .getDatacenter("mydatacenter", mockInventoryNavigator));
    }
}
