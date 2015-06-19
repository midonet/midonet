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
package org.midonet.api.servlet;

import javax.servlet.ServletContext;

import com.typesafe.config.Config;

import org.midonet.api.rest_api.RestApiModule;
import org.midonet.api.vtep.VtepMockableDataClientFactory;
import org.midonet.cluster.ClusterConfig;
import org.midonet.cluster.southbound.vtep.VtepDataClientFactory;

/**
 * Jersey servlet module for MidoNet REST API application.
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
        install(new RestApiModule() {
            protected void bindVtepDataClientFactory() {
                bind(VtepDataClientFactory.class)
                    .to(VtepMockableDataClientFactory.class)
                    .asEagerSingleton();
            }
        });
    }

    @Override
    protected void installConfigApi(ClusterConfig clusterConf) {
    }
}
