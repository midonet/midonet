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
package org.midonet.midolman.guice

import com.google.inject.PrivateModule

import org.midonet.cluster.state.{ZookeeperStateStorage, StateStorage}

/**
 * Guice module to install dependencies for cluster state data access.
 */
class StateStorageModule extends PrivateModule {

    protected override def configure(): Unit = {
        bind(classOf[StateStorage]).to(classOf[ZookeeperStateStorage]).asEagerSingleton()
        expose(classOf[StateStorage])
    }

}
