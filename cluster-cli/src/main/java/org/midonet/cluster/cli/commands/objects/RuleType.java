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

package org.midonet.cluster.cli.commands.objects;

import org.midonet.cluster.data.ZoomEnum;
import org.midonet.cluster.data.ZoomEnumValue;
import org.midonet.cluster.models.Topology;

/** The type for a chain rule. */
@ZoomEnum(clazz = Topology.Rule.Type.class)
public enum RuleType {
    @ZoomEnumValue(value = "LITERAL_RULE")
    LITERAL,
    @ZoomEnumValue(value = "NAT_RULE")
    NAT,
    @ZoomEnumValue(value = "JUMP_RULE")
    JUMP,
    @ZoomEnumValue(value = "TRACE_RULE")
    TRACE
}
