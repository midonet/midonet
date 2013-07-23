/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.api.rest_api;

import com.google.inject.servlet.GuiceFilter;
import org.midonet.api.auth.AuthConfig;
import org.midonet.api.auth.cors.CorsConfig;
import org.midonet.api.serialization.ObjectMapperProvider;
import org.midonet.api.serialization.WildCardJacksonJaxbJsonProvider;
import org.midonet.api.servlet.JerseyGuiceServletContextListener;
import org.midonet.api.version.VersionParser;
import org.midonet.api.zookeeper.ExtendedZookeeperConfig;
import com.sun.jersey.api.client.config.ClientConfig;
import com.sun.jersey.api.client.config.DefaultClientConfig;
import com.sun.jersey.test.framework.AppDescriptor;
import com.sun.jersey.test.framework.WebAppDescriptor;
import org.cassandraunit.utils.EmbeddedCassandraServerHelper;

import java.net.URI;
import java.util.UUID;

public class FuncTest {
    static final ClientConfig config = new DefaultClientConfig();

    // Cassandra settings
    public static final String cassandraCluster = "midonet";
    public static final String monitoringCassandraKeyspace =
            "midonet_monitoring_keyspace";
    public static final String monitoringCassandraColumnFamily =
            "midonet_monitoring_column_family";
    public final static String cassandraServers = "127.0.0.1:9171";
    public final static int cassandraMaxActiveConns = 3;
    public final static String CASSANDRA_SERVERS = "cassandra-servers";
    public final static String CASSANDRA_CLUSTER = "cassandra-cluster";
    public final static String MONITORING_CASSANDRA_KEYSPACE =
            "monitoring-cassandra_keyspace";
    public final static String MONITORING_CASSANDRA_COLUMN_FAMILY =
            "monitoring-cassandra_column_family";
    public final static String MONITORING_CASSANDRA_REPLICATION_FACTOR =
            "monitoring-cassandra_replication_factor";
    public final static String MONITORING_CASSANDRA_EXPIRATION_TIMEOUT =
            "monitoring-cassandra_expiration_timeout";

    public final static String BASE_URI_CONFIG = "rest_api-base_uri";
    public final static String CONTEXT_PATH = "/test";
    public final static String OVERRIDE_BASE_URI =
            "http://127.0.0.1:9998" + CONTEXT_PATH;

    public static int replicationFactor = 1;
    public static int ttlInSecs = 1000;

    public static WildCardJacksonJaxbJsonProvider jacksonJaxbJsonProvider;

    public static final WebAppDescriptor.Builder getBuilder() {

        // Start the cassandra server.  Calling this multiple times does not
        // do anything.
        try {
            EmbeddedCassandraServerHelper.startEmbeddedCassandra();
        } catch (Exception ex) {
            throw new RuntimeException("Could not start Cassandra");
        }

        VersionParser parser = new VersionParser();
        ObjectMapperProvider mapperProvider = new ObjectMapperProvider();
        jacksonJaxbJsonProvider = new WildCardJacksonJaxbJsonProvider(
                mapperProvider, parser);
        config.getSingletons().add(jacksonJaxbJsonProvider);

        return new WebAppDescriptor.Builder()
                .contextListenerClass(JerseyGuiceServletContextListener.class)
                .filterClass(GuiceFilter.class)
                .servletPath("/")
                .contextParam(
                        getConfigKey(CorsConfig.GROUP_NAME,
                                CorsConfig.ALLOW_ORIGIN_KEY), "*")
                .contextParam(
                        getConfigKey(CorsConfig.GROUP_NAME,
                                CorsConfig.ALLOW_HEADERS_KEY),
                        "Origin, X-Auth-Token, Content-Type, Accept")
                .contextParam(
                        getConfigKey(CorsConfig.GROUP_NAME,
                                CorsConfig.ALLOW_METHODS_KEY),
                        "GET, POST, PUT, DELETE, OPTIONS")
                .contextParam(
                        getConfigKey(CorsConfig.GROUP_NAME,
                                CorsConfig.EXPOSE_HEADERS_KEY), "Location")
                .contextParam(
                        getConfigKey(AuthConfig.GROUP_NAME,
                                AuthConfig.AUTH_PROVIDER),
                        "org.midonet.api.auth.MockAuthService")
                .contextParam(
                        getConfigKey(ExtendedZookeeperConfig.GROUP_NAME,
                                ExtendedZookeeperConfig.USE_MOCK_KEY), "true")
                .contextParam(
                        getConfigKey(ExtendedZookeeperConfig.GROUP_NAME,
                                "midolman_root_key"), "/test/midolman")
                .contextParam(CASSANDRA_SERVERS, cassandraServers)
                .contextParam(CASSANDRA_CLUSTER, cassandraCluster)
                .contextParam(MONITORING_CASSANDRA_KEYSPACE,
                        monitoringCassandraKeyspace)
                .contextParam(MONITORING_CASSANDRA_COLUMN_FAMILY,
                        monitoringCassandraColumnFamily)
                .contextParam(MONITORING_CASSANDRA_REPLICATION_FACTOR,
                        "" + replicationFactor)
                .contextParam(MONITORING_CASSANDRA_EXPIRATION_TIMEOUT,
                        "" + ttlInSecs)
                .contextPath(CONTEXT_PATH).clientConfig(config);
    }

    public static final AppDescriptor appDesc = getBuilder().build();

    public static final AppDescriptor appDescOverrideBaseUri =
        getBuilder().contextParam(BASE_URI_CONFIG, OVERRIDE_BASE_URI).build();

    public static UUID getUuidFromLocation(URI location) {
        if (location == null) {
            return null;
        }
        String[] tmp = location.toString().split("/");
        return UUID.fromString(tmp[tmp.length - 1]);
    }

    public static String getConfigKey(String section, String key) {
        return section + "-" + key;
    }
}
