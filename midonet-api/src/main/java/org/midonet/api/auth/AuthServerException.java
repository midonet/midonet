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
package org.midonet.api.auth;

/**
 * AuthServerException class for bad HTTP response from external auth system..
 */
public class AuthServerException extends AuthException {

    private static final long serialVersionUID = 1L;
    private int status;

    /**
     * Create a AuthServerException object with a message.
     *
     * @param message
     *            Error message.
     * @param status
     *            Server error code
     */
    public AuthServerException(String message, int status) {
        super(message);
        this.status = status;
    }

    /**
     * Create a AuthServerException object with no message and wrap a
     * Throwable object.
     *
     * @param e
     *            Throwable object
     * @param status
     *            Server error code
     */
    public AuthServerException(Throwable e, int status) {
        super(e);
        this.status = status;
    }

    /**
     * Create a AuthServerException object with a message and wrap a
     * Throwable object.
     *
     * @param message
     *            Error message.
     * @param e
     *            Throwable object
     * @param status
     *            Server error code
     */
    public AuthServerException(String message, Throwable e, int status) {
        super(message, e);
        this.status = status;
    }

    public int getStatus() {
        return status;
    }
}
