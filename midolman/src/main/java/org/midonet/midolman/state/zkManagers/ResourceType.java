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

package org.midonet.midolman.state.zkManagers;

/*
 * Enum identifying resource type for string building. This cuts down on the
 * amount of code necessary by allowing us to identify the resource in
 * function arguments, instead of making a new function for each resource type.
 */
public enum ResourceType {
    PORT("port"),
    RULE("rule"),
    ROUTER("router"),
    BRIDGE("bridge"),
    ROUTE("route"),
    CHAIN("chain");

    private final String name;
    ResourceType(String name) {
        this.name = name;
    }
    public String toString() {
        return name;
    }
}