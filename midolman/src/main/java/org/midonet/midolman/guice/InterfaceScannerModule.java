package org.midonet.midolman.guice;

import com.google.inject.PrivateModule;
import org.midonet.midolman.host.scanner.DefaultInterfaceScanner;
import org.midonet.midolman.host.scanner.InterfaceScanner;

public class InterfaceScannerModule extends PrivateModule {

    @Override
    protected void configure() {
        binder().requireExplicitBindings();
        bind(InterfaceScanner.class).to(DefaultInterfaceScanner.class);
        expose(InterfaceScanner.class);
    }
}
