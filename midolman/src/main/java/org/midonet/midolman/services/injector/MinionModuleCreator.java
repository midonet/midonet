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

package org.midonet.midolman.services.injector;

import com.google.inject.Module;

/** Implementations of MinionModuleCreator can be used to add modules to the
 * injectors that provide parameters to the minions. During initialization, and
 * before starting the minion, the classpath is scanned for implementions of
 * MinionModuleCreator. An instance of each implementation is created and its
 * create method is executed to return a module that will be added when
 * launching the minion. To allow enabling and disabling Minion module creators,
 * only implementations that are annotated with @AgentMinionModule are used. */
public interface MinionModuleCreator {
    Module create();
}
