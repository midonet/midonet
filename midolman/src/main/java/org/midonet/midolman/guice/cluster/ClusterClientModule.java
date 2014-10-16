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
package org.midonet.midolman.guice.cluster;

import org.midonet.midolman.state.Directory;
import org.midonet.cluster.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class defines dependency bindings for DataClient and Client
 * interfaces.  It extends DataClusterClientModule that defines bindings
 * for DataClient, and it defines the bindings specific to Client.
 */
public class ClusterClientModule extends DataClusterClientModule {

    private static final Logger log = LoggerFactory
            .getLogger(ClusterClientModule.class);

    @Override
    protected void configure() {
        super.configure();

        requireBinding(Directory.class);

        bind(Client.class)
                .to(LocalClientImpl.class)
                .asEagerSingleton();
        expose(Client.class);
    }
}
