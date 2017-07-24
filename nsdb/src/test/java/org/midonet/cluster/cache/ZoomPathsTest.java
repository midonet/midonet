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

package org.midonet.cluster.cache;

import java.util.UUID;

import com.typesafe.config.ConfigFactory;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import org.midonet.cluster.models.Topology;
import org.midonet.cluster.storage.MidonetBackendConfig;

public class ZoomPathsTest {

    private MidonetBackendConfig config;

    @Before
    public void before() throws Exception {
        config = new MidonetBackendConfig(
            ConfigFactory.parseString("zookeeper.root_key : /midonet"),
            false, false, false);
    }

    @Test
    public void testObjectClassPath() {
        // Given a paths instance.
        ZoomPaths paths = new ZoomPaths(config);

        // When computing the object class path.
        String path = paths.objectClassPath(Topology.Host.class);

        // Then the path should be correct.
        Assert.assertEquals(path, "/midonet/zoom/0/models/Host");
    }

    @Test
    public void testObjectPath() {
        // Given a paths instance.
        ZoomPaths paths = new ZoomPaths(config);

        // When computing the object class path.
        UUID id = UUID.randomUUID();
        String classPath = paths.objectClassPath(Topology.Host.class);
        String path = paths.objectPath(classPath, id.toString());

        // Then the path should be correct.
        Assert.assertEquals(path, "/midonet/zoom/0/models/Host/" + id.toString());
    }

    @Test
    public void testStatePath() {
        // Given a paths instance.
        ZoomPaths paths = new ZoomPaths(config);

        // When computing the object class path.
        UUID owner = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        String path = paths.statePath(owner, Topology.Host.class, id);

        // Then the path should be correct.
        Assert.assertEquals(path, "/midonet/zoom/0/state/" + owner.toString() +
                                  "/Host/" + id.toString());
    }

    @Test
    public void testKeyPath() {
        // Given a paths instance.
        ZoomPaths paths = new ZoomPaths(config);

        // When computing the object class path.
        UUID owner = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        String statePath = paths.statePath(owner, Topology.Host.class, id);
        String path = paths.keyPath(owner, Topology.Host.class, id, "key");

        // Then the path should be correct.
        Assert.assertEquals(path, "/midonet/zoom/0/state/" + owner.toString() +
                                  "/Host/" + id.toString() + "/key");
    }

}
