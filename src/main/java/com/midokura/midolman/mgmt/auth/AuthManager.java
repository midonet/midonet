package com.midokura.midolman.mgmt.auth;

import javax.ws.rs.core.SecurityContext;

public class AuthManager {

    private static final String adminRole = "Admin";

    public AuthManager() {
    }

    public static boolean canInitZooKeeper(SecurityContext context) {
        return (context.isUserInRole(adminRole));
    }
    
    
    

}
