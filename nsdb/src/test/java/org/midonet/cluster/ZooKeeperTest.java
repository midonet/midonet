/*
 * Copyright 2017 Midokura SARL
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

package org.midonet.cluster;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.RetryNTimes;
import org.apache.curator.test.TestingServer;
import org.apache.curator.utils.ZKPaths;

public abstract class ZooKeeperTest {

    private static final Map<Class<? extends ZooKeeperTest>, TestingServer> SERVERS =
        new ConcurrentHashMap<>();

    protected static final String ROOT = "/test";
    protected CuratorFramework curator;

    protected static void beforeAll(Class<? extends ZooKeeperTest> clazz)
        throws Exception {
        TestingServer server = new TestingServer();
        server.start();
        SERVERS.put(clazz, server);
    }

    protected static void afterAll(Class<? extends ZooKeeperTest> clazz)
        throws IOException {
        SERVERS.remove(clazz).stop();
    }

    protected void before() throws Exception {
        TestingServer server = SERVERS.get(getClass());
        curator = CuratorFrameworkFactory.newClient(server.getConnectString(),
                                                    30000, 30000,
                                                    new RetryNTimes(2, 1000));
        curator.start();
        curator.blockUntilConnected();
        if (curator.checkExists().forPath(ROOT) != null) {
            ZKPaths.deleteChildren(curator.getZookeeperClient().getZooKeeper(),
                                   ROOT, false);
        } else {
            curator.create().forPath(ROOT);
        }
    }

    protected void after() {
        curator.close();
    }

}
