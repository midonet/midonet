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

package org.midonet.cluster.services.conman;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation for a service container. It matches a specific service container
 * implementation with a container type (e.g. IPSEC, QUAGGA, HAPROXY, etc.).
 * Classes with this annotation must extend the ServiceContainer trait and
 * may implement custom operations when the container status has changed.
 */
@Inherited
@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface Container {

    /**
     * The container type name.
     */
    String name();

    /**
     * The container handler version. If there are multiple implementations with
     * the same container name, the container management service selects the one
     * with the greatest version.
     */
    int version();

}
