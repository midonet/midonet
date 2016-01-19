/*
 * Copyright 2016 Midokura SARL
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

package org.midonet.cluster.auth.keystone

import com.sun.jersey.api.client.UniformInterfaceException

import org.midonet.cluster.auth.AuthException

class KeystoneException(val url: String, message: String, inner: Exception)
    extends AuthException(message, inner) {

    def this(url: String) =
        this(url, s"Keystone v2 request `$url` failed", null)

    def this(url: String, inner: Exception) =
        this(url, s"Keystone v2 request `$url` failed", inner)

}

class KeystoneUnauthorizedException(url: String, message: String, inner: Exception)
    extends KeystoneException(url, message, inner) {

    def this(url: String, inner: UniformInterfaceException) =
        this(url, "Invalid Keystone v2 credentials: " +
                  s"${inner.getResponse.getEntity(classOf[AnyRef])}", inner)

}

class KeystoneConnectionException(url: String, inner: Exception)
    extends KeystoneException(url, s"Connection to Keystone v2 failed", inner)