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
package org.midonet.brain.southbound.vtep;

/**
 * A checked exception for the VTEP vtep state.
 */
public class VtepStateException extends VtepException {

    private static final long serialVersionUID = -29438946408794685L;

    public VtepStateException(VtepEndPoint vtep, String message) {
        super(vtep, message);
    }

    public VtepStateException(VtepEndPoint vtep, String message,
                              Throwable throwable) {
        super(vtep, message, throwable);
    }

    public VtepStateException(VtepEndPoint vtep, Throwable throwable) {
        super(vtep, throwable);
    }

}
