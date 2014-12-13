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
package org.midonet.brain.configuration;

import org.midonet.brain.ClusterNodeConfig;
import org.midonet.config.ConfigBool;
import org.midonet.config.ConfigGroup;
import org.midonet.config.ConfigString;

/**
 * This class is used to configure the Cluster Node embedded in the REST API
 * and will be removed as soon as the Cluster becomes a first-class service
 * in MidoNet.
 */
@Deprecated
@ConfigGroup(EmbeddedClusterNodeConfig.GROUP_NAME)
public interface EmbeddedClusterNodeConfig extends ClusterNodeConfig {

    public final static String GROUP_NAME = "midobrain";

    /**
     * Used to tell the REST API whether the Cluster services should be
     * ran as part of the REST API server. The key name is confusing, but
     * kept to respect backwards compatibility with existing deployments.
     */
    @ConfigBool(key = "vxgw_enabled", defaultValue = false)
    public boolean isEmbeddingEnabled();

    @ConfigString(key = "host_id", defaultValue = "")
    public String getHostId();

    @ConfigString(key = "properties_file",
                  defaultValue = "/midonet_cluster_node_id.properties")
    public String getHostPropertiesFilePath();
}
