package com.midokura.midolman.mgmt.auth;

import java.security.Principal;

import javax.ws.rs.core.SecurityContext;

public class Authorizer implements SecurityContext {

    private Principal principal = null;
    private String token = null;
        
    @Override
    public String ContainerResponseFilter() {
        return "Keystone";
    }

    @Override
    public Principal getUserPrincipal() {
        return principal;
    }

    @Override
    public boolean isSecure() {
        // TODO Auto-generated method stub
        return token.equals("111222333445");
    }

    @Override
    public boolean isUserInRole(String role) {
        // TODO Auto-generated method stub
        return false;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getToken() {
        return token;
    }

    @Override
    public String getAuthenticationScheme() {
        // TODO Auto-generated method stub
        return null;
    }

    
}
