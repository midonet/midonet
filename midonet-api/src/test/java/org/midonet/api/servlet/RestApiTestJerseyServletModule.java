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
package org.midonet.api.servlet;

import javax.servlet.ServletContext;

import com.google.inject.AbstractModule;

import org.midonet.api.rest_api.DataclientTopologyBackdoor;
import org.midonet.api.rest_api.RestApiModule;
import org.midonet.api.rest_api.TopologyBackdoor;

/**
 * Jersey servlet module for MidoNet REST API application, for testing.
 */
public class RestApiTestJerseyServletModule extends RestApiJerseyServletModule {

    public RestApiTestJerseyServletModule(ServletContext servletContext) {
        super(servletContext);
    }

    @Override
    protected boolean clusterEmbedEnabled() {
        return false;
    }

    @Override
    protected void installRestApiModule() {
        install(new AbstractModule() {
            @Override
            protected void configure() {
                // Gives access to the backend storage, based on the dataclient
                bind(TopologyBackdoor.class)
                    .to(DataclientTopologyBackdoor.class);
            }
        });
        install(new RestApiModule());
    }

}
