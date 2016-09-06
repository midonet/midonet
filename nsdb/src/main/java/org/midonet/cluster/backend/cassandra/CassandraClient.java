/*
 * Copyright 2015 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.midonet.cluster.backend.cassandra;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.TimeUnit;

import com.datastax.driver.core.*;
import com.datastax.driver.core.policies.*;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.recipes.locks.InterProcessMutex;
import org.apache.curator.retry.RetryNTimes;
import org.midonet.cluster.storage.CassandraConfig;
import org.midonet.cluster.storage.MidonetBackendConfig;
import org.midonet.util.eventloop.Reactor;
import org.midonet.util.eventloop.TryCatchReactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.concurrent.Future;
import scala.concurrent.Promise;
import scala.concurrent.Promise$;

public class CassandraClient {

    private static final Logger log =
            LoggerFactory.getLogger(CassandraClient.class);

    private Session session = null;

    private Promise<Session> sessionPromise = Promise$.MODULE$.apply();
    private final MidonetBackendConfig backendConf;
    private final String[] servers;
    private final String serversStr;
    private final String clusterName;
    private final String keyspaceName;
    private final int port;
    private final int replicationFactor;
    private String[] schema;
    private String[] schemaTableNames;

    private static Reactor theReactor = null;

    static {
        theReactor = new TryCatchReactor("cassandra-connection-manager", 1);
    }

    private final static Map<String, Cluster> CLUSTERS = new HashMap<>();
    private final static Map<String, Map<String, Session>> SESSIONS = new HashMap<>();

    public CassandraClient(MidonetBackendConfig backendConf,
                           CassandraConfig cassandraConf,
                           String keyspaceName,
                           String[] schema, String[] schemaTableNames) {
        this.serversStr = cassandraConf.servers();
        this.clusterName = cassandraConf.cluster();
        this.keyspaceName = keyspaceName;
        this.servers  = serversStr.split(",");
        this.schema = schema;
        this.schemaTableNames = schemaTableNames;
        this.backendConf = backendConf;

        if (schema != null &&
            schema.length != schemaTableNames.length) {
            throw new IllegalArgumentException(
                    "Schema array length and schema table " +
                    "name array length must match");
        }

        this.replicationFactor = cassandraConf.replication_factor();
        int p = 9042;
        for (int i = this.servers.length-1; i >= 0; i--) {
            String[] parts = this.servers[i].split(":");
            if (parts.length == 2) {
                this.servers[i] = parts[0];
                p = Integer.parseInt(parts[1]);
            }
        }
        this.port = p;
    }

    public Future<Session> connect() {
        if (serversStr == null || serversStr.isEmpty()) {
            sessionPromise.tryFailure(
                new Exception("Cassandra is not configured. MidoNet will " +
                              "not store flow state on Cassandra and will " +
                              "rely on local storage (if enabled) to handle " +
                              "agent reboots and port migrations."));
        } else {
            theReactor.submit(new Runnable() {
                @Override
                public void run() {
                    synchronized (CLUSTERS) {
                        if (session == null)
                            _connect(10);
                    }
                }
            });
        }
        return sessionPromise.future();
    }

    private void createAndUseKeyspace() {
        String q = "CREATE KEYSPACE IF NOT EXISTS " + keyspaceName +
            " WITH REPLICATION = { 'class' : 'SimpleStrategy', " +
                                  "'replication_factor' : " + replicationFactor + "};";
        this.session.execute(q);
        this.session.execute("USE " + keyspaceName + ";");
    }

    private boolean schemaExists() {
        this.session.execute("USE system;");
        String q = "SELECT columnfamily_name "
            + "FROM system.schema_columnfamilies where keyspace_name = '"
            + keyspaceName + "';";
        ResultSet tables = this.session.execute(q);
        Set<String> existingTables = new HashSet<String>();
        Iterator<Row> iter = tables.iterator();
        while (iter.hasNext()) {
            existingTables.add(iter.next().getString(0));
        }
        return existingTables.containsAll(Arrays.asList(schemaTableNames));
    }

    private void maybeCreateSchema() throws Exception {
        if (!schemaExists()) {
            CuratorFramework curator = CuratorFrameworkFactory.builder()
                .connectString(backendConf.hosts())
                .connectionTimeoutMs(5*1000) // 10 second
                .sessionTimeoutMs(5*1000) // 5 second
                .retryPolicy(new RetryNTimes(5, 1000)) // 5 times, 1sec interval
                .build();
            InterProcessMutex lock = new InterProcessMutex(curator,
                    "/midonet/cassandraSchemaLock");
            try {
                curator.start();
                lock.acquire();
                if (!schemaExists()) {
                    createAndUseKeyspace();
                    if (this.schema != null) {
                        for (int i=0; i<schema.length; i++) {
                            this.session.executeAsync(schema[i])
                                .get(10, TimeUnit.SECONDS);
                        }
                    }
                }
            } finally {
                lock.release();
                curator.close();
            }
        }
        this.session.execute("USE " + keyspaceName + ";");
    }

    private void _connect(int retries) {
        Cluster cluster = CLUSTERS.get(serversStr);
        boolean firstSession = false;

        Map<String, Session> sessions = null;
        try {
            if (cluster == null) {
                firstSession = true;
                LoadBalancingPolicy rr = new RoundRobinPolicy();
                LoadBalancingPolicy latencyAware = LatencyAwarePolicy.builder(rr).build();
                QueryOptions queryOpts = new QueryOptions().
                        setConsistencyLevel(ConsistencyLevel.QUORUM);
                SocketOptions sockOpts = new SocketOptions().
                        setKeepAlive(true).
                        setConnectTimeoutMillis(3000);

                cluster = Cluster.builder().
                        addContactPoints(servers).
                        withPort(this.port).
                        withRetryPolicy(DowngradingConsistencyRetryPolicy.INSTANCE).
                        withReconnectionPolicy(new ExponentialReconnectionPolicy(100L, 5000L)).
                        withLoadBalancingPolicy(latencyAware).
                        withQueryOptions(queryOpts).
                        withClusterName(clusterName).
                        withSocketOptions(sockOpts).build();

                SESSIONS.put(serversStr, new HashMap<String, Session>());
            }

            sessions = SESSIONS.get(serversStr);
            this.session = sessions.get(keyspaceName);

            if (this.session == null) {
                this.session = cluster.connect();
                maybeCreateSchema();
                sessions.put(keyspaceName, this.session);
                log.info("Connection to Cassandra key space {} ESTABLISHED", keyspaceName);
            }

            if (firstSession)
                CLUSTERS.put(serversStr, cluster);

            sessionPromise.success(session);

        } catch (Exception e) {
            log.error("Connection to Cassandra key space " + keyspaceName + " FAILED", e);
            if (this.session != null) {
                this.session.close();
                this.session = null;
                if (sessions != null)
                    sessions.remove(keyspaceName);
            }
            scheduleReconnect(retries);
        }
    }

    private void scheduleReconnect(final int retries) {
        log.info("Scheduling Cassandra reconnection retry");
        if (retries > 0) {
            theReactor.schedule(new Runnable() {
                @Override
                public void run() {
                    log.info("Trying to reconnect to Cassandra");
                    synchronized (CLUSTERS) {
                        _connect(retries - 1);
                    }
                }
            }, 30, TimeUnit.SECONDS);
        } else {
            log.error("Unable to connect to cassandra after 10 retries, giving up");
            sessionPromise.tryFailure(new Exception("Unable to connect to Cassandra"));
        }
    }

    public Session session() {
        return session;
    }
}
