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

package org.midonet.cluster.rest_api;

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
