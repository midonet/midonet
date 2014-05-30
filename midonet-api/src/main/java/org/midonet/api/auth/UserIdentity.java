/*
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.api.auth;

import java.util.HashSet;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;

/**
 * Class that holds the identity information of a user.
 */
public class UserIdentity {

    private String tenantId = null;
    private String tenantName = null;
    private String userId = null;
    private String token = null;
    private final Set<String> roles = new HashSet<String>();

    /**
     * @return the tenantId
     */
    public String getTenantId() {
        return tenantId;
    }

    /**
     * @param tenantId
     *            the tenantId to set
     */
    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    /**
     * @return the userId
     */
    public String getUserId() {
        return userId;
    }

    /**
     * @param userId
     *            the userId to set
     */
    public void setUserId(String userId) {
        this.userId = userId;
    }

    /**
     * @return the token
     */
    public String getToken() {
        return token;
    }

    /**
     * @param token
     *            the token to set
     */
    public void setToken(String token) {
        this.token = token;
    }

    /**
     * @param role
     *            Role to add.
     */
    public void addRole(String role) {
        this.roles.add(role);
    }

    /**
     * @param role
     *            Role to check.
     * @return True if the user has this role.
     */
    public boolean hasRole(String role) {
        return roles.contains(role);
    }

    /**
     * Get the tenant name.
     *
     * @return tenantName.
     */
    public String getTenantName() {
        return tenantName;
    }

    /**
     * Set the tenant name.
     *
     * @param tenantName
     */
    public void setTenantName(String tenantName) {
        this.tenantName = tenantName;
    }

    /*
     * (non-Javadoc)
     *
     * @see java.lang.Object#toString()
     */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("UserIdentity: userId=" + this.userId);
        sb.append(", token=" + this.token);
        sb.append(", tenantId=" + this.tenantId);
        sb.append(", tenantName=" + this.tenantName);
        sb.append(", roles=" + StringUtils.join(this.roles, '|'));
        return sb.toString();
    }
}
