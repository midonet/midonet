/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */

package org.midonet.api.rest_api;

/**
 * Custom HTTP statuses which are not included in Jersey's status enum.
 */
public enum Status {
    METHOD_NOT_ALLOWED (405, "Method Not Allowed");

    private final int code;
    private final String reason;

    Status(int statusCode, String reason) {
        this.code = statusCode;
        this.reason = reason;
    }

    /**
     * Get the status code.
     *
     * @return The status code in int.
     */
    public int getStatusCode() {
        return this.code;
    }

    /**
     * Get the reason of the status code.
     *
     * @return The reason in String.
     */
    public String getReason() {
        return this.reason;
    }
}
