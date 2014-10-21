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
package org.midonet.api.serialization;

import org.codehaus.jackson.map.ObjectMapper;

import javax.ws.rs.core.MediaType;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Class that allows registration of view mixins to be used for serialization.
 */
public class ViewMixinProvider {

    private static ConcurrentHashMap<Class<?>, Class<?>> mixinMap =
            new ConcurrentHashMap<Class<?>, Class<?>>();

    // Those that want to use the View ObjectMapper must register in this Set.
    private static Set<String> viewMediaTypes = new HashSet<String>();

    public synchronized static void registerViewMixin(
            Class<?> target, Class<?> mixin) {
        mixinMap.putIfAbsent(target, mixin);
    }

    public synchronized static void setViewMixins(ObjectMapper mapper) {

        for (Map.Entry<Class<?>, Class<?>> entry : mixinMap.entrySet()) {
            mapper.getSerializationConfig().addMixInAnnotations(
                    entry.getKey(), entry.getValue());
        }
    }

    public static void registerMediaType(MediaType mediaType) {
        registerMediaType(mediaType.toString());
    }

    public static void registerMediaType(String mediaType) {
        viewMediaTypes.add(mediaType.toString());
    }

    public static boolean isRegisteredMediaType(MediaType mediaType) {
        return isRegisteredMediaType(mediaType.toString());
    }

    public static boolean isRegisteredMediaType(String mediaType) {
        return viewMediaTypes.contains(mediaType);
    }
}
