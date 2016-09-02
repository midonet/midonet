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

package org.midonet.client.dto;

import java.net.URI;
import java.util.Objects;
import java.util.UUID;

public class DtoVppBinding {

    public UUID portId;
    public UUID hostId;
    public String interfaceName;
    public URI uri;
    public URI host;
    public URI port;

    @Override
    public boolean equals(Object object) {
        if (null == object) return false;
        if (this == object) return true;
        if (object.getClass() != getClass()) return false;
        DtoVppBinding that = (DtoVppBinding) object;
        return Objects.equals(portId, that.portId) &&
               Objects.equals(hostId, that.hostId) &&
               Objects.equals(interfaceName, that.interfaceName) &&
               Objects.equals(uri, that.uri) &&
               Objects.equals(host, that.host) &&
               Objects.equals(port, that.port);
    }

    @Override
    public int hashCode() {
        return Objects.hash(portId, hostId, interfaceName, uri, host, port);
    }
}
