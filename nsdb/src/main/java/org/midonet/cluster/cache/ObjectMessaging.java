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

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import com.google.protobuf.Message;
import com.google.protobuf.MessageOrBuilder;

import org.apache.commons.lang.StringUtils;

import org.midonet.cluster.models.Topology;

final class ObjectMessaging {

    private static final Map<Class<?>, ObjectSerializer> SERIALIZERS;

    static {
        Map<Class<?>, ObjectSerializer> serializers =
            new HashMap<>(ObjectCache.classes().length);

        for (Class<?> clazz : ObjectCache.classes()) {
            if (MessageOrBuilder.class.isAssignableFrom(clazz)) {
                @SuppressWarnings("unchecked")
                ObjectSerializer serializer =
                    new ObjectSerializer((Class<? extends MessageOrBuilder>) clazz);
                serializers.put(clazz, serializer);
            }
        }

        SERIALIZERS = Collections.unmodifiableMap(serializers);
    }

    /**
     * Returns the {@link ObjectSerializer} for the specified class.
     * @param clazz The object class.
     * @return The {@link ObjectSerializer}.
     */
    static ObjectSerializer serializerOf(Class<?> clazz) {
        return SERIALIZERS.get(clazz);
    }

    /**
     * Gets the tenant for the specified object.
     * @param message The object Protocol Buffers message.
     * @return The tenant identifier or an empty string if the object does
     * not have any data or does not have a tenant.
     */
    static String tenantOf(Message message) {
        if (message == null) {
            return StringUtils.EMPTY;
        }
        if (message instanceof Topology.Network) {
            return ((Topology.Network) message).getTenantId();
        } else if (message instanceof  Topology.Chain) {
            return ((Topology.Chain) message).getTenantId();
        } else if (message instanceof Topology.PortGroup) {
            return ((Topology.PortGroup) message).getTenantId();
        } else if (message instanceof Topology.Router) {
            return ((Topology.Router) message).getTenantId();
        } else if (message instanceof Topology.QosPolicy) {
            return ((Topology.QosPolicy) message).getTenantId();
        }
        return StringUtils.EMPTY;
    }

}
