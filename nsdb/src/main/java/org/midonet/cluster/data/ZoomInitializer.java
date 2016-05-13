/*
 * Copyright 2016 Midokura SARL
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

package org.midonet.cluster.data;

import org.midonet.cluster.data.storage.StateStorage;
import org.midonet.cluster.data.storage.Storage;

/** Implementations of ZoomInitializer can be used to inject code into the setup
 * of the midonet backend. During initialization, and before building the
 * backend storage, the classpath is scanned for implementions of
 * ZoomInitializer. An instance of each implementation is created and its setup
 * method is executed. To allow enabling and disabling Zoom initializers, only
 * implementations that are annotated with @ZoomInit are used. */
public interface ZoomInitializer {
    void setup(Storage store, StateStorage stateStore);
}
