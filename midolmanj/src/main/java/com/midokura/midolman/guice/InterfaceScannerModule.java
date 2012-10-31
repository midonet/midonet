package com.midokura.midolman.guice;

import com.google.inject.PrivateModule;
import com.midokura.midolman.host.scanner.DefaultInterfaceScanner;
import com.midokura.midolman.host.scanner.InterfaceScanner;

public class InterfaceScannerModule extends PrivateModule {


    @Override
    protected void configure() {
        binder().requireExplicitBindings();
        bind(InterfaceScanner.class).to(DefaultInterfaceScanner.class);
        expose(InterfaceScanner.class);
    }


}
