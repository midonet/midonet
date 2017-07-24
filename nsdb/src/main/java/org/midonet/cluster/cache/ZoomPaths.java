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

import javax.annotation.Nonnull;

import org.midonet.cluster.storage.MidonetBackendConfig;

/**
 * Computes the ZooKeeper object mapper paths. This class will not be
 * needed staring with MidoNet 5.4.1, which will expose the paths from
 * storage.
 */
public final class ZoomPaths {

    private static final int ZOOM_VERSION = 0;

    private final String rootPath;

    public ZoomPaths(MidonetBackendConfig config) {
        this.rootPath = String.format("%s/zoom/%d", config.rootKey(),
                                      ZOOM_VERSION);
    }

    /**
     * Returns the ZooKeeper path for the specified object class.
     * @param clazz The class.
     * @return The path.
     */
    public String objectClassPath(@Nonnull Class<?> clazz) {
        return String.format("%s/models/%s", rootPath, clazz.getSimpleName());
    }

    /**
     * Returns the ZooKeeper path for the specified object.
     * @param classPath The class path obtained with method
     * {@link ZoomPaths#objectClassPath(Class)}.
     * @param id The object identifier.
     * @return The path
     */
    public String objectPath(@Nonnull String classPath, @Nonnull String id) {
        return String.format("%s/%s", classPath, id);
    }

    /**
     * Returns the ZooKeeper path for the specified state.
     * @param owner The state owner.
     * @param clazz The class.
     * @param id The object identifier.
     * @return The path.
     */
    public String statePath(@Nonnull UUID owner, @Nonnull Class<?> clazz,
                            @Nonnull UUID id) {
        return String.format("%s/state/%s/%s/%s", rootPath, owner.toString(),
                             clazz.getSimpleName(), id.toString());
    }

    /**
     * Returns the ZooKeeper path for the specified state key.
     * @param owner The state owner.
     * @param clazz The class.
     * @param id The object identifier.
     * @param key The state key.
     * @return The path.
     */
    public String keyPath(@Nonnull UUID owner, @Nonnull Class<?> clazz,
                          @Nonnull UUID id, @Nonnull String key) {
        return String.format("%s/state/%s/%s/%s/%s", rootPath, owner.toString(),
                             clazz.getSimpleName(), id.toString(), key);
    }

}
