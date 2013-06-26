/*
* Copyright 2013 Midokura PTE
*/
package org.midonet.midolman.version.guice;

import com.google.inject.PrivateModule;
import org.midonet.midolman.version.DataVersionProvider;
import org.midonet.midolman.version.state.ZkDataVersionProvider;
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

        bind(DataVersionProvider.class)
                .to(ZkDataVersionProvider.class).asEagerSingleton();
        expose(DataVersionProvider.class);
    }

}
