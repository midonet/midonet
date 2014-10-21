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
package org.midonet.api.auth.keystone;

import org.midonet.api.auth.AuthException;

/**
 * KeystoneConnectionException class for bad JSON response from Keystone.
 */
public class KeystoneConnectionException extends AuthException {

    private static final long serialVersionUID = 1L;

    /**
     * Create a KeystoneConnectionException object with a message.
     *
     * @param message
     *            Error message.
     */
    public KeystoneConnectionException(String message) {
        super(message);
    }

    /**
     * Create a KeystoneConnectionException object with no message and wrap a
     * Throwable object.
     *
     * @param e
     *            Throwable object
     */
    public KeystoneConnectionException(Throwable e) {
        super(e);
    }

    /**
     * Create a KeystoneConnectionException object with a message and wrap a
     * Throwable object.
     *
     * @param message
     *            Error message.
     * @param e
     *            Throwable object
     */
    public KeystoneConnectionException(String message, Throwable e) {
        super(message, e);
    }
}
