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

import org.midonet.config.ConfigBool;
import org.midonet.config.ConfigGroup;
import org.midonet.config.ConfigString;
import org.midonet.config.HostIdConfig;
import org.midonet.cluster.config.CassandraConfig;
import org.midonet.cluster.config.ZookeeperConfig;

@ConfigGroup(MidoBrainConfig.GROUP_NAME)
public interface MidoBrainConfig
    extends ZookeeperConfig, CassandraConfig, HostIdConfig {

    public final static String GROUP_NAME = "midobrain";

    /**
     * Gets a flag indicating whether the VXGW service is enabled.
     *
     * @return True if the VXGW service is enabled, false otherwise.
     */
    @ConfigBool(key = "vxgw_enabled", defaultValue = false)
    public boolean getVxGwEnabled();

    /**
     * Gets the unique identifier stored in the configuration file.
     *
     * @return The unique identifier.
     */
    @ConfigString(key = "host_uuid", defaultValue = "")
    public String getHostId();

    /**
     * Gets the path of the properties file.
     *
     * @return The path of the properties file.
     */
    @ConfigString(key = "properties_file",
                  defaultValue = "/var/lib/tomcat7/webapps/host_uuid.properties")
    public String getHostPropertiesFilePath();
}
