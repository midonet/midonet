/*
* Copyright 2013 Midokura PTE
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
