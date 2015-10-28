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

import java.io.IOException;

import javax.ws.rs.core.UriInfo;

import scala.Tuple3;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import org.apache.commons.lang3.StringUtils;

import org.midonet.cluster.rest_api.annotation.ApiResource;
import org.midonet.cluster.rest_api.version.Version;
import org.midonet.cluster.services.rest_api.ResourceProvider;

import static org.midonet.cluster.services.rest_api.resources.MidonetResource.ResourceContext;

@JsonSerialize(using = Application.Serializer.class)
public class Application {

    public static class Serializer extends JsonSerializer<Application> {
        @Override
        public void serialize(Application app, JsonGenerator gen,
                              SerializerProvider serializers) throws IOException {
            UriInfo uriInfo = app.resContext.uriInfo();
            gen.writeStartObject();
            gen.writeStringField("uri", uriInfo.getBaseUri().toString());
            gen.writeStringField("version", Version.CURRENT);
            for (Tuple3<String, ApiResource, Class<Object>> entry : app.resProvider.all()) {
                if (StringUtils.isNotBlank(entry._2().name())) {
                    gen.writeStringField(entry._2().name(),
                                         uriFor(uriInfo, entry._1()));
                }
                if (StringUtils.isNotBlank(entry._2().template())) {
                    gen.writeStringField(entry._2().template(),
                                         templateFor(uriInfo, entry._1()));
                }
            }
            gen.writeEndObject();
        }

        private String uriFor(UriInfo uriInfo, String segment) {
            return uriInfo.getAbsolutePathBuilder().segment(segment)
                          .build().toString();
        }

        private String templateFor(UriInfo uriInfo, String segment) {
            return uriInfo.getAbsolutePathBuilder().segment(segment)
                          .build().toString() + "/{id}";
        }
    }

    @JsonIgnore
    private final ResourceProvider resProvider;

    @JsonIgnore
    private final ResourceContext resContext;

    public Application(ResourceProvider resProvider, ResourceContext resContext) {
        this.resProvider = resProvider;
        this.resContext = resContext;
    }

}
