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

package org.midonet.cluster.rest_api.models;

import java.net.URI;
import java.util.UUID;

import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;

import com.fasterxml.jackson.annotation.JsonCreator;

import org.midonet.cluster.data.ZoomClass;
import org.midonet.cluster.data.ZoomEnum;
import org.midonet.cluster.data.ZoomEnumValue;
import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.models.Topology;
import org.midonet.cluster.rest_api.ResourceUris;
import org.midonet.cluster.util.IPAddressUtil;
import org.midonet.packets.IPv4;

@ZoomClass(clazz = Topology.LogResource.class)
public class LogResource extends UriResource {

    @ZoomField(name = "id")
    public UUID id;

    @NotNull
    @ZoomField(name = "type")
    public LogType type;

    @ZoomField(name = "file_name")
    public String fileName;

    @Pattern(regexp = IPv4.regex, message = "is an invalid IP format")
    @ZoomField(name = "ip_address", converter = IPAddressUtil.Converter.class)
    public String ipAddress;

    @Override
    public URI getUri() {
        return absoluteUri(ResourceUris.LOG_RESOURCES(), id);
    }

    @ZoomEnum(clazz = Topology.LogResource.Type.class)
    public enum LogType {
        @ZoomEnumValue("FILE") FILE,
        @ZoomEnumValue("SYSLOG") SYSLOG,
        @ZoomEnumValue("UNDERLAY_SYSLOG") UNDERLAY_SYSLOG;

        @JsonCreator
        @SuppressWarnings("unused")
        public static LogType forValue(String v) {
            return v == null ? null : valueOf(v);
        }
    }
}
