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
package org.midonet.midolman.version.guice;

import java.util.Comparator;

import com.google.inject.PrivateModule;
import com.google.inject.TypeLiteral;

import org.midonet.midolman.SystemDataProvider;
import org.midonet.midolman.state.ZkSystemDataProvider;
import org.midonet.midolman.version.VersionComparator;

/**
 * Serialization configuration module
 */
public class VersionModule extends PrivateModule {

    @Override
    protected void configure() {
        binder().requireExplicitBindings();

        bind(new TypeLiteral<Comparator<String>>(){})
            .annotatedWith(VerCheck.class)
            .to(VersionComparator.class)
            .asEagerSingleton();
        expose(new TypeLiteral<Comparator<String>>(){})
            .annotatedWith(VerCheck.class);

        bind(SystemDataProvider.class)
            .to(ZkSystemDataProvider.class).asEagerSingleton();
        expose(SystemDataProvider.class);
    }
}
