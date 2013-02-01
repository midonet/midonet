package org.midonet.midolman.guice;

import com.google.inject.Key;
import com.google.inject.PrivateModule;
import com.google.inject.Provider;
import com.google.inject.name.Names;

import org.midonet.midolman.host.scanner.DefaultInterfaceScanner;
import org.midonet.midolman.host.scanner.InterfaceScanner;
import org.midonet.util.eventloop.Reactor;
import org.midonet.util.eventloop.TryCatchReactor;

public class InterfaceScannerModule extends PrivateModule {


    @Override
    protected void configure() {
        binder().requireExplicitBindings();
        bind(InterfaceScanner.class).to(DefaultInterfaceScanner.class);
        expose(InterfaceScanner.class);

        bind(Reactor.class).annotatedWith(
                Names.named(DefaultInterfaceScanner.INTERFACE_REACTOR))
                .toProvider(InterfaceScannerReactorProvider.class)
                .asEagerSingleton();

        expose(Key.get(Reactor.class,
                Names.named(
                        DefaultInterfaceScanner.INTERFACE_REACTOR)));
    }

    public static class InterfaceScannerReactorProvider
        implements Provider<Reactor> {

        @Override
        public Reactor get() {
            return new TryCatchReactor("inteface-scanner", 1);
        }
    }
}
