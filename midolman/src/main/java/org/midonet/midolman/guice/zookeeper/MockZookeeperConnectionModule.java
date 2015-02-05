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
package org.midonet.midolman.guice.zookeeper;

import javax.inject.Singleton;

import org.midonet.midolman.state.Directory;
import org.midonet.midolman.state.MockDirectory;
import org.midonet.midolman.state.ZookeeperConnectionWatcher;

public class MockZookeeperConnectionModule  extends ZookeeperConnectionModule {

    public MockZookeeperConnectionModule() {
        super(ZookeeperConnectionWatcher.class);
    }

    @Override
    protected void bindZookeeperConnection() {
        // no binding since we are mocking
    }

    @Override
    protected void bindDirectory() {
            bind(Directory.class)
                .to(MockDirectory.class)
                .in(Singleton.class);
    }
}
