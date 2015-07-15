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

package org.midonet.cluster.state

import com.google.inject.PrivateModule

/**
 * This Guice module is dedicated to declare dependencies that are exposed to
 * MidoNet components which use merged maps in a testing environment.
 */
class MergedMapTestModule extends PrivateModule {
    override def configure(): Unit = {
        bind(classOf[MergedMapState]).to(classOf[InMemoryMergedMapStorage])
        expose(classOf[MergedMapState])
    }
}
