/*
 * Copyright 2015 Midokura SARL
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

package org.midonet.vtep;

/**
 * An exception class for vtep-specific issues
 */
public class VtepException extends Exception {
    private final VtepEndPoint vtep;

    public VtepException(VtepEndPoint vtep, String msg) {
        super("VTEP " + vtep + ": " + msg);
        this.vtep = vtep;
    }

    public VtepException(VtepEndPoint vtep, Throwable cause) {
        super("VTEP " + vtep + " exception: " + cause.getMessage(), cause);
        this.vtep = vtep;
    }

    public VtepEndPoint getVtep() {
        return vtep;
    }
}


