package com.midokura.midolman.mgmt.auth;

import java.security.Principal;

import javax.ws.rs.core.Context;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;

public class MockAuthorizer implements SecurityContext {

    private Principal principal = null;

    @Context
    UriInfo uriInfo;

    public MockAuthorizer() {
        principal = new Principal() {
            public String getName() {
                return "Mock";
            }
        };
    }

    @Override
    public Principal getUserPrincipal() {
        return principal;
    }

    @Override
    public boolean isSecure() {
        return true;
    }

    @Override
    public boolean isUserInRole(String role) {
        return true;
    }

//     public void setTenantUser(TenantUser tenantUser) {
//         this.tenantUser = tenantUser;
//     }

//     public TenantUser getTenantUser() {
//         return tenantUser;
//     }

    @Override
    public String getAuthenticationScheme() {
        //return "Keystone";
        return "Mock";
    }
}
