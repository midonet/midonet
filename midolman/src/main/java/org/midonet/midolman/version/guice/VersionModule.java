/*
* Copyright 2013 Midokura PTE
*/
package org.midonet.midolman.version.guice;

import com.google.inject.PrivateModule;
import org.midonet.midolman.SystemDataProvider;
import org.midonet.midolman.state.ZkSystemDataProvider;
import org.midonet.midolman.version.VersionComparator;

import java.util.Comparator;

/**
 * Serialization configuration module
 */
public class VersionModule extends PrivateModule {

    @Override
    protected void configure() {
        binder().requireExplicitBindings();

        bind(Comparator.class)
            .annotatedWith(VerCheck.class)
            .to(VersionComparator.class)
            .asEagerSingleton();
        expose(Comparator.class).annotatedWith(VerCheck.class);

        bind(SystemDataProvider.class)
                .to(ZkSystemDataProvider.class).asEagerSingleton();
        expose(SystemDataProvider.class);
    }
}
