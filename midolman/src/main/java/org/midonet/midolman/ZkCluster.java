/*
 * Copyright (c) 2013 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.midolman;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import com.google.inject.Guice;
import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.PrivateModule;
import com.google.inject.Provider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.cluster.DataClient;
import org.midonet.cluster.data.host.Host;
import org.midonet.config.ConfigProvider;
import org.midonet.midolman.config.MidolmanConfig;
import org.midonet.midolman.guice.MockCacheModule;
import org.midonet.midolman.guice.cluster.ClusterClientModule;
import org.midonet.midolman.guice.config.ConfigProviderModule;
import org.midonet.midolman.guice.serialization.SerializationModule;
import org.midonet.midolman.guice.zookeeper.ZookeeperConnectionModule;
import org.midonet.midolman.monitoring.store.Store;
import org.midonet.midolman.version.guice.VersionModule;

/** This static class offers a static method startCluster() which loads a
 *  minimal guice profile including the necessary midolman components to start
 *  a functional zookeeper connection and have access to an implementation
 *  of DataClient. This method takes as an input parameter a
 *  local path to a midolman configuration file and is threadsafe.
 *  It us guaranteed that the cluster is started at most once.
 *  Once the cluster has been started, a DataClient instance can
 *  be obtained by calling the static method getClusterClient(). */
public class ZkCluster {

    private ZkCluster() { }

    static final Logger log = LoggerFactory.getLogger(ZkCluster.class);

    private static Injector clusterInjector = null;

    private static Injector getInjector() {
        if (clusterInjector == null)
            throw new IllegalStateException("Cluster has not been started yet");
        return clusterInjector;
    }

    public static DataClient getClusterClient() {
        return getInjector().getInstance(DataClient.class);
    }

    synchronized public static void startCluster(String configFilePath) {
        if (clusterInjector != null)
            return;

        log.info("Injecting custom ZkCluster juice profile");
        clusterInjector = Guice.createInjector(
            new ZookeeperConnectionModule(),
            new VersionModule(),
            new ConfigProviderModule(configFilePath),
            new ClosedStoreModule(),
            new ClusterClientModule(),
            new SerializationModule(),
            new MockCacheModule(),
            new MidolmanConfigModule()
        );
    }

    /** Integration testing method that prints hosts currently registed in the
    *   the cluster. */
    public static void printHost(DataClient dc) {
        try {
            for (Host h: dc.hostsGetAll()) {
                log.info("found host {}", h);
            }
        } catch (Exception e) {
            log.error("error while querying hosts", e);
        }
    }

    /** Integration method that starts a minimal ZkCluster module and prints the
     *  hosts currently registered in the cluster. You can invoke this method
     *  from the command line with $ java -cp full_jar org.midonet.ZkCluster.*/
    public static void main(String[] args) {

        String configFilePath = "./midolman/conf/midolman.conf";
        if (args.length > 0)
            configFilePath = args[0];

        if (!Files.isReadable(Paths.get(configFilePath))) {
            log.error("missing or invalid config file \"{}\"", configFilePath);
            System.exit(1);
        }

        startCluster(configFilePath);

        printHost(getClusterClient());

        System.exit(0);
    }

    public static class MidolmanConfigModule extends PrivateModule {

        @Override
        protected void configure() {
            binder().requireExplicitBindings();

            requireBinding(ConfigProvider.class);

            bind(MidolmanConfig.class)
                .toProvider(MidolmanConfigProvider.class)
                .asEagerSingleton();
            expose(MidolmanConfig.class);
        }

        public static class MidolmanConfigProvider
                implements Provider<MidolmanConfig> {

            @Inject
            ConfigProvider configProvider;

            @Override
            public MidolmanConfig get() {
                return configProvider.getConfig(MidolmanConfig.class);
            }
        }

    }

    public static class ClosedStoreModule extends PrivateModule {

        @Override
        protected void configure() {
            bind(Store.class)
                .toProvider(ClosedStoreProvider.class)
                .asEagerSingleton();
            expose(Store.class);
        }

        public static class ClosedStoreProvider implements Provider<Store> {
            @Override public Store get() { return new ClosedStore(); }
        }

    }

    public static class ClosedStore implements Store {

        public void initialize() {}
        public void addMetricTypeToTarget(String targetIdentifier, String type) {}
        public void addMetricToType(String type, String metricName) {}
        public void addTSPoint(String type, String targetIdentifier,
                               String metricName, long time, long value) {}
        public long getTSPoint(String type, String targetIdentifier,
                               String metricName, long time) { return 0L; }
        public Map<String, Long> getTSPoints(String type, String targetIdentifier,
                                             String metricName, long timeStart,
                                             long timeEnd) { return null; }
        public List<String> getMetricsTypeForTarget(String targetIdentifier) {
            return null;
        }
        public List<String> getMetricsForType(String type) { return null; }

    }
}
