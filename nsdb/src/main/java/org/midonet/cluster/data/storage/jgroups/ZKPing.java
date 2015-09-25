/**
 *  Copyright 2005-2014 Red Hat, Inc.
 *
 *  Red Hat licenses this file to you under the Apache License, version
 *  2.0 (the "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 *  implied.  See the License for the specific language governing
 *  permissions and limitations under the License.
 */
package org.midonet.cluster.data.storage.jgroups;

import java.util.Properties;

import com.typesafe.config.Config;

import org.jgroups.conf.ClassConfigurator;

import org.midonet.cluster.storage.MidonetBackendConfig;
import org.midonet.conf.MidoNodeConfigurator;

import scala.Some;

/**
 * A workaround "org.jgroups.protocols" prefix limitation.
 */
public class ZKPing extends ConfigurableZooKeeperPing {
    private static short WF_ZK_PING_ID = (short) (CONFIGURABLE_ZK_PING_ID + 1);

    static {
        ClassConfigurator.addProtocol(WF_ZK_PING_ID, ZKPing.class);
        System.out.println("ZKPing class registered");
    }

    public ZKPing() {}

    @Override
    public void init() throws Exception {
        // TODO: How can we pass None to boostrapConfig from Java?
        Config config = MidoNodeConfigurator.bootstrapConfig(new Some(""));
        MidonetBackendConfig backendConfig = new MidonetBackendConfig(config);

        String zkURL = backendConfig.hosts();
        if (zkURL != null) {
            System.out.println("Connecting to ZK hosts: " + zkURL);
            connection = zkURL;
        }

        // password
        password = "";
        super.init();
    }

    @Override
    protected byte[] getAuth() {
        return password.getBytes();
    }
}
