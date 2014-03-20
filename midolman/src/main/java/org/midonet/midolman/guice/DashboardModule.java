package org.midonet.midolman.guice;

import com.google.inject.PrivateModule;
import org.midonet.config.ConfigProvider;
import org.midonet.midolman.services.DashboardService;

public class DashboardModule extends PrivateModule {
    @Override
    protected void configure() {
        binder().requireExplicitBindings();

        requireBinding(ConfigProvider.class);

        bind(DashboardService.class).asEagerSingleton();
        expose(DashboardService.class);
    }
}
