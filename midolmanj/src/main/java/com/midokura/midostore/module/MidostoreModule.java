/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midostore.module;

import java.lang.reflect.Field;

import com.google.inject.AbstractModule;
import com.google.inject.Inject;
import com.google.inject.MembersInjector;
import com.google.inject.Provider;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.google.inject.TypeLiteral;
import com.google.inject.matcher.Matchers;
import com.google.inject.spi.TypeEncounter;
import com.google.inject.spi.TypeListener;

import com.midokura.midolman.agent.state.HostZkManager;
import com.midokura.midolman.config.MidolmanConfig;
import com.midokura.midolman.state.Directory;
import com.midokura.midolman.state.ZkManager;
import com.midokura.midostore.LocalMidostoreClient;
import com.midokura.midostore.MidostoreClient;
import com.midokura.midostore.services.MidostoreSetupService;

public class MidostoreModule extends AbstractModule {
    @Override
    protected void configure() {

	bindListener(Matchers.any(),
		     new ZkManagerBuilderTypeListener());

	bind(MidostoreClient.class)
	    .to(LocalMidostoreClient.class)
	    .asEagerSingleton();

	bind(MidostoreSetupService.class).in(Singleton.class);
    }

    @Provides
    @Singleton
    public HostZkManager buildHostZkManager(Directory directory, MidolmanConfig config) {
	return new HostZkManager(directory, config.getMidolmanRootKey());
    }

    public static class ZkManagerFactory {

	@Inject
	Directory directory;

	@Inject
	MidolmanConfig config;

	public ZkManager create(Class<? extends ZkManager> zkManagerClass) {
	    try {
		return
		    zkManagerClass
			.getConstructor(Directory.class, String.class)
			.newInstance(directory,
				     config.getMidolmanRootKey());
	    } catch (Exception e) {
		throw new RuntimeException("Could not create ZKManager of type: " + zkManagerClass);
	    }
	}
    }

    private static class ZkManagerBuilderTypeListener implements TypeListener {

	@Override
	public <I> void hear(TypeLiteral<I> typeLiteral, TypeEncounter<I> encounter) {
	    Class<? super I> type = typeLiteral.getRawType();
	    for (Field field : type.getDeclaredFields()) {
		if (ZkManager.class.isAssignableFrom(field.getType())) {
		    if (field.isAnnotationPresent(Inject.class) ||
			field.isAnnotationPresent(javax.inject.Inject.class)) {
			encounter.register(
			    new ZkManagerInjector<I>(
				field,
				encounter.getProvider(ZkManagerFactory.class)));
		    }
		}
	    }
	}

	private class ZkManagerInjector<T> implements MembersInjector<T> {

	    private Field field;
	    private Provider<ZkManagerFactory> factoryProvider;

	    public ZkManagerInjector(Field field, Provider<ZkManagerFactory> factoryProvider) {
		this.field = field;
		this.factoryProvider = factoryProvider;
		this.field.setAccessible(true);
	    }

	    @Override
	    public void injectMembers(T instance) {
		try {
		    //noinspection unchecked
		    Class<? extends ZkManager> type =
			(Class<? extends ZkManager>) field.getType();

		    field.set(instance,
			      factoryProvider.get().create(type));

		} catch (IllegalAccessException e) {
		    throw new RuntimeException("Could not set zkManager field " + field + " on instance " + instance, e);
		}
	    }
	}
    }
}
