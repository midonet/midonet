package com.midokura.midolman.mgmt.auth;

import java.util.UUID;

import javax.ws.rs.core.SecurityContext;

import com.midokura.midolman.mgmt.data.OwnerQueryable;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midolman.state.ZkStateSerializationException;

public class AuthManager {

    private static final String adminRole = "Admin";
    private static final String serviceProviderRole = "ServiceProvider";

    public AuthManager() {
    }

    public static boolean isAdmin(SecurityContext context) {
        return (context.isUserInRole(adminRole));
    }

    public static boolean isServiceProvider(SecurityContext context) {
        return (isAdmin(context) || context.isUserInRole(serviceProviderRole));
    }

    public static boolean isSelf(SecurityContext context, UUID id) {
        return (isAdmin(context))
                || context.getUserPrincipal().getName().equals(id.toString());
    }

    public static boolean isOwner(SecurityContext context, OwnerQueryable dao,
            UUID id) throws StateAccessException, ZkStateSerializationException {
        if (isAdmin(context)) {
            return true;
        }
        UUID ownerId = dao.getOwner(id);
        if (ownerId != null) {
            return (context.getUserPrincipal().getName().equals(ownerId
                    .toString()));
        } else {
            return false;
        }

    }
}
