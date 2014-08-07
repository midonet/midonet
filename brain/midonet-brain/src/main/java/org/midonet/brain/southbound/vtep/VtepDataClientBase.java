package org.midonet.brain.southbound.vtep;

import java.net.ConnectException;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.TimerTask;
import java.util.UUID;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;

import com.google.common.util.concurrent.Monitor;

import org.opendaylight.controller.sal.connection.ConnectionConstants;
import org.opendaylight.controller.sal.core.Node;
import org.opendaylight.ovsdb.lib.message.TableUpdates;
import org.opendaylight.ovsdb.lib.table.internal.Table;
import org.opendaylight.ovsdb.lib.table.vtep.Physical_Switch;
import org.opendaylight.ovsdb.plugin.ConfigurationService;
import org.opendaylight.ovsdb.plugin.Connection;
import org.opendaylight.ovsdb.plugin.ConnectionService;
import org.opendaylight.ovsdb.plugin.InventoryServiceInternal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rx.Observable;
import rx.Subscription;
import rx.functions.Action1;
import rx.functions.Func1;
import rx.subjects.PublishSubject;

import org.midonet.brain.southbound.vtep.model.PhysicalSwitch;
import org.midonet.brain.southbound.vtep.model.VtepModelTranslator;
import org.midonet.packets.IPv4Addr;
import org.midonet.util.functors.Callback;

/**
 * The base class for a connection aware VTEP data client. The class maintains
 * the connection state, and exposes observables and methods that allow users
 * to monitor the connection, wait for reaching a specific state, and install
 * connection callbacks.
 */
public abstract class VtepDataClientBase implements VtepDataClient {

    private static final Logger log =
        LoggerFactory.getLogger(VtepDataClientBase.class);

    // Static configuration

    private static final long CONNECTION_MONITOR_INTERVAL_MILLIS = 60000;
    private static final byte MAX_IDLE_CONNECTION_MONITOR_INTERVALS = 5;

    // Connection subscription

    private static class CallbackSubscription implements Subscription {
        private boolean subscribed = true;
        private final Callback<VtepDataClient, VtepException> callback;

        public CallbackSubscription(
            Callback<VtepDataClient, VtepException> callback) {
            this.callback = callback;
        }

        public void unsubscribe() {
            subscribed = false;
        }

        public boolean isUnsubscribed() {
            return !subscribed;
        }
    }

    // State and synchronization

    // The data client uses the following states:
    // - DISCONNECTED: The state when:
    //   (a) the client is created, and;
    //   (b) when the connection was disconnected by the server/network via the
    //       onDisconnected() method and the list of users was empty.
    // - CONNECTING: The state during an asynchronous connect operation to the
    //               VTEP. The client is connecting when:
    //   (a) the connect() method is called on a DISCONNECTED client, or;
    //   (b) when the connection monitor timer calls the onConnectionMonitor()
    //       method, and;
    //     (b.1.1) the connection is in use by at least one user, or;
    //     (b.1.2) the connection has not yet expired, and;
    //     (b.2) the connection is in the BROKEN state.
    // - CONNECTED: The connecting completed successfully, and the OVS-DB API
    //              called the onConnected() method.
    // - DISCONNECTED: The state
    // - BROKEN: The state when the connection was disconnected by the server/
    //           network via the onDisconnected() method and there are still
    //           users for this connection.
    // - DISPOSED: The state when the client connection was no longer in use and
    //             expired, indicating that the connection resources (sockets,
    //             threads) can be released, and allowing the
    //             VtepDataClientProvider to remove the references to this
    //             client.
    //
    // The state transitions are the following:
    //
    //               connect()
    // DISCONNECTED ----------> CONNECTING
    //               connect(), ConnectException
    // DISCONNECTED ----------------------------> BROKEN
    //               connect(), Exception
    // DISCONNECTED ---------------------> DISPOSED
    //             onConnected()
    // CONNECTING --------------> CONNECTED
    //                         onDisconnected(), users=0
    // CONNECTING / CONNECTED ---------------------------> DISCONNECTED
    //                         onDisconnected(), users>0
    // CONNECTING / CONNECTED ---------------------------> BROKEN
    //         onConnectionMonitor(), not expired
    // BROKEN -----------------------------------> CONNECTING
    //         onConnectionMonitor(), not expired, Exception
    // BROKEN ----------------------------------------------> BROKEN

    private final Monitor monitor = new Monitor();
    @GuardedBy("monitor")
    private volatile VtepDataClient.State state =
        State.DISCONNECTED;

    private final Monitor.Guard isConnectable = new Monitor.Guard(monitor) {
        @Override
        public boolean isSatisfied() {
            return State.DISPOSED != state;
        }
    };
    private final Monitor.Guard isNotConnecting = new Monitor.Guard(monitor) {
        @Override
        public boolean isSatisfied() {
            return State.CONNECTING != state;
        }
    };
    private final Monitor.Guard isDisconnectable = new Monitor.Guard(monitor) {
        @Override
        public boolean isSatisfied() {
            return State.CONNECTING != state &&
                   State.DISPOSED != state;
        }
    };
    private final Monitor.Guard isDisconnected = new Monitor.Guard(monitor) {
        @Override
        public boolean isSatisfied() {
            return State.DISCONNECTED == state ||
                   State.BROKEN == state ||
                   State.DISPOSED == state;
        }
    };
    private final Monitor.Guard isBrokenOrUnused = new Monitor.Guard(monitor) {
        @Override
        public boolean isSatisfied() {
            return State.BROKEN == state || users.isEmpty();
        }
    };
    private final Monitor.Guard isNotDisposed = new Monitor.Guard(monitor) {
        @Override
        public boolean isSatisfied() {
            return State.DISPOSED != state;
        }
    };

    protected final VtepEndPoint endPoint;
    final java.util.UUID connectionId;

    final ConfigurationService configurationService;
    final ConnectionService connectionService;

    private final Map<ConnectionConstants, String> params = new HashMap<>();
    private final Set<UUID> users = new HashSet<>();

    private final PublishSubject<VtepDataClient.State>
        stateSubject = PublishSubject.create();

    // State variables

    @GuardedBy("monitor")
    volatile Node node = null;
    @GuardedBy("monitor")
    volatile PhysicalSwitch physicalSwitch = null;

    private final List<Subscription> subscriptions = new ArrayList<>();

    private final Queue<CallbackSubscription> callbacks = new LinkedList<>();

    private final TimerTask connectionMonitorTask = new TimerTask() {
        @Override
        public void run() {
            onConnectionMonitor();
        }
    };
    private byte connectionExpirationCounter = 0;

    /**
     * @param endPoint The VTEP management end-point.
     * @param configurationService The OVS-DB configuration service.
     * @param connectionService The OVS-DB connection service.
     */
    VtepDataClientBase(VtepEndPoint endPoint,
                       ConfigurationService configurationService,
                       ConnectionService connectionService) {
        // A unique identifier for this client in the OVS-DB service.
        connectionId = java.util.UUID.randomUUID();

        this.endPoint = endPoint;
        this.configurationService = configurationService;
        this.connectionService = connectionService;

        params.put(ConnectionConstants.ADDRESS,
                   endPoint.mgmtIp.toString());
        params.put(ConnectionConstants.PORT,
                   Integer.toString(endPoint.mgmtPort));

        Action1<Connection> connected = new Action1<Connection>() {
            @Override
            public void call(Connection connection) {
                onConnected(connection);
            }
        };
        Action1<Connection> disconnected = new Action1<Connection>() {
            @Override
            public void call(Connection connection) {
                onDisconnected(connection);
            }
        };
        Func1<Connection, Boolean> connectionFilter =
            new Func1<Connection, Boolean>() {
                @Override
                public Boolean call(Connection connection) {
                    return connection.getIdentifier()
                        .equals(connectionId.toString());
                }
            };

        subscriptions.add(connectionService
                              .connectedObservable()
                              .filter(connectionFilter)
                              .subscribe(connected));
        subscriptions.add(connectionService
                              .disconnectedObservable()
                              .filter(connectionFilter)
                              .subscribe(disconnected));

        VtepDataClientFactory.timer.scheduleAtFixedRate(
            connectionMonitorTask,
            CONNECTION_MONITOR_INTERVAL_MILLIS,
            CONNECTION_MONITOR_INTERVAL_MILLIS);
    }

    @Override
    public IPv4Addr getManagementIp() {
        return this.endPoint.mgmtIp;
    }

    @Override
    public int getManagementPort() {
        return this.endPoint.mgmtPort;
    }

    @Override
    public IPv4Addr getTunnelIp() {
        if (physicalSwitch == null || physicalSwitch.tunnelIps == null ||
            physicalSwitch.tunnelIps.isEmpty()) {
            return null;
        }
        return IPv4Addr.apply(physicalSwitch.tunnelIps.iterator().next());
    }

    /**
     * Waits for the VTEP data client to be connected to the VTEP.
     *
     * @throws VtepStateException The client reached a state from which it
     * can no longer become connected.
     */
    @Override
    public VtepDataClient awaitConnected() throws VtepStateException {
        monitor.enterWhenUninterruptibly(isNotConnecting);
        try {
            assertState(State.CONNECTED);
        } finally {
            monitor.leave();
        }
        return this;
    }

    /**
     * Waits for the VTEP data client to be connected to the VTEP the specified
     * timeout interval.
     *
     * @param timeout The timeout interval.
     * @param unit The interval unit.
     * @throws VtepStateException The client reached a state from which it
     * can no longer become connected.
     * @throws TimeoutException The timeout interval expired.
     */
    @Override
    public VtepDataClient awaitConnected(long timeout, TimeUnit unit)
        throws VtepStateException, TimeoutException {
        if (monitor.enterWhenUninterruptibly(isNotConnecting, timeout, unit)) {
            try {
                assertState(State.CONNECTED);
            } finally {
                monitor.leave();
            }
        } else if (State.CONNECTED != state) {
            throw new TimeoutException("Timeout expired.");
        }
        return this;
    }

    /**
     * Waits for the VTEP data client to be disconnected from the VTEP.
     *
     * @throws VtepStateException The client reached a state from which it
     * can no longer become disconnected.
     */
    @Override
    public VtepDataClient awaitDisconnected() throws VtepStateException {
        monitor.enterWhenUninterruptibly(isDisconnected);
        try {
            assertAnyState(State.DISCONNECTED,
                           State.BROKEN,
                           State.DISPOSED);
        } finally {
            monitor.leave();
        }
        return this;
    }

    /**
     * Waits for the VTEP data client to be disconnected from the VTEP the
     * specified timeout interval.
     *
     * @param timeout The timeout interval.
     * @param unit The interval unit.
     * @throws VtepStateException The client reached a state from which it
     * can no longer become discconnected.
     * @throws TimeoutException The timeout interval expired.
     */
    @Override
    public VtepDataClient awaitDisconnected(long timeout, TimeUnit unit)
        throws VtepStateException, TimeoutException {
        if (monitor.enterWhenUninterruptibly(isDisconnected, timeout, unit)) {
            try {
                assertState(State.DISCONNECTED);
            } finally {
                monitor.leave();
            }
        } else if (State.DISCONNECTED != state) {
            throw new TimeoutException("Timeout expired.");
        }
        return this;
    }

    /**
     * Waits for the VTEP data client to reach the specified state. The method
     * throws an exception if the specified state can no longer be reached.
     * @param state The state.
     */
    @SuppressWarnings("unused")
    public VtepDataClient awaitState(final VtepDataClient.State state)
        throws VtepStateException {
        if (State.DISPOSED == this.state &&
            State.DISPOSED != state) {
            throw new VtepStateException(endPoint, "State unreachable");
        }
        monitor.enterWhenUninterruptibly(new Monitor.Guard(monitor) {
            @Override
            public boolean isSatisfied() {
                return state == VtepDataClientBase.this.state;
            }
        });
        monitor.leave();
        return this;
    }

    /**
     * Waits for the VTEP data client to reach the specified state an amount of
     * time.
     * @param state The state.
     * @param timeout The timeout interval.
     * @param unit The interval unit.
     */
    @Override
    public VtepDataClient awaitState(final VtepDataClient.State state,
                                     long timeout, TimeUnit unit)
        throws TimeoutException {
        if (monitor.enterWhenUninterruptibly(
            new Monitor.Guard(monitor) {
                @Override
                public boolean isSatisfied() {
                    return state == VtepDataClientBase.this.state;
                }
            }, timeout, unit)) {
            monitor.leave();
        }
        return this;
    }

    /**
     * Specifies a callback method to execute when the VTEP becomes connected.
     * If the client is already CONNECTED, the callback onSuccess() method is
     * synchronously called immediately.
     *
     * When the client is CONNECTING, if the connection completes successfully,
     * the client calls the onSuccess() method of the callback instance.
     *
     * Otherwise, the callback is queued and the onSuccess() method is called
     * when the client eventually becomes connected.
     *
     * If the client is DISPOSED, the client calls the onError() method.
     *
     * @param callback A callback instance.
     */
    public Subscription onConnected(
        @Nonnull Callback<VtepDataClient, VtepException> callback) {
        CallbackSubscription subscription = new CallbackSubscription(callback);

        monitor.enter();
        try {
            if (State.CONNECTED == state) {
                callback.onSuccess(this);
            } else {
                callbacks.add(subscription);
            }
        } finally {
            monitor.leave();
        }

        return subscription;
    }

    /**
     * Gets the current client state.
     */
    @Override
    @GuardedBy("monitor")
    public VtepDataClient.State getState() {
        return state;
    }

    /**
     * Provides an observable notifying of the changes to the connection state.
     */
    @Override
    public Observable<VtepDataClient.State> stateObservable() {
        return stateSubject.asObservable();
    }

    /**
     * Provides an observable producing a stream of updates from the VTEP.
     */
    @Override
    public Observable<TableUpdates> updatesObservable() {
        return connectionService.updatesObservable();
    }

    /**
     * Connects to the VTEP. If the VTEP is already connected or connecting,
     * the method does nothing. If the VTEP is disconnecting, the method throws
     * an exception.
     *
     * @param user The caller for this connection.
     */
    protected void connect(java.util.UUID user) throws VtepStateException {

        // Acquire a monitor lock while validating the state.
        if (!monitor.enterIf(isConnectable)) {
            throw new VtepStateException(
                endPoint, "The VTEP client cannot connect.");
        }

        try {
            log.info("Connecting to VTEP on {} for user {}", endPoint, user);

            users.add(user);

            // Do nothing if the client is already connecting or connected.
            if (state == State.CONNECTING ||
                state == State.CONNECTED) {
                return;
            }

            state = State.CONNECTING;
            stateSubject.onNext(State.CONNECTING);

            physicalSwitch = null;

            // Begin connecting to the VTEP. The connection service will
            // notify via the observable when the connection is established.
            node = connectionService.connect(connectionId.toString(), params);
        } catch (Exception e) {
            // NETTY throws exceptions using sun.misc.Unsafe, hence we catch
            // all exceptions and distinguish accoring to instance class.
            if (e.getClass().equals(ConnectException.class) ||
                e.getClass().equals(SocketException.class)) {
                log.error("Connecting to VTEP on {} failed: {}", endPoint,
                          e.getMessage());
                state = State.BROKEN;
                stateSubject.onNext(State.BROKEN);
            } else {
                log.error("Unexpected error when connecting to VTEP {}: {}",
                          endPoint, e.getMessage(), e);
                state = State.DISPOSED;
                stateSubject.onNext(State.DISPOSED);
            }
        } finally {
            monitor.leave();
        }
    }

    /**
     * Disconnects from the VTEP. If the VTEP is already disconnecting or
     * disconnected, the method does nothing. If the connection state is
     * connecting, the method throws an exception.
     *
     * The method does not disconnect if the specified user did not previously
     * call the connect method, or if there are other users still using
     * the connection.
     *
     * @param user The user for this connection.
     * @param lazyDisconnect If true, the methods does dispose the VTEP
     *                       client immediately, event if there are no more
     *                       users for this connection. Instead the client
     *                       remains connected for a period of time equal to
     *                       CONNECTION_MONITOR_INTERVAL_MILLIS times
     *                       MAX_IDLE_CONNECTION_MONITOR_INTERVALS. Only after
     *                       this interval expires, the connection will be
     *                       closed if no longer in use.
     * @throws VtepStateException The client could not disconnect because of
     * an invalid service state after a number of retries.
     */
    @Override
    public void disconnect(java.util.UUID user, boolean lazyDisconnect)
        throws VtepStateException {

        // Acquire a monitor lock while validating the state.
        if (!monitor.enterIf(isDisconnectable)) {
            throw new VtepStateException(
                endPoint, "The VTEP client cannot disconnect.");
        }

        try {
            log.info("Disconnecting from VTEP on {} on behalf of {}",
                     endPoint, user);

            // Remove the user from the users list.
            users.remove(user);

            // If there are no more users for this connection, close the
            // connection
            if (users.isEmpty() && !lazyDisconnect) {
                dispose();
            } else {
                log.debug("Connection maintained for {} users or until "
                          + "expiration", users.size());
            }
        } finally {
            monitor.leave();
        }
    }

    /**
     * Disposes the current VTEP data client, closing any VTEP connection.
     */
    protected void dispose() {
        // Ignore if the client has already been disposed.
        if (!monitor.enterIf(isNotDisposed)) {
            log.info("Client for VTEP {} already disposed {}", endPoint);
            return;
        }
        try {
            log.info("Disposing VTEP client {}", endPoint);

            users.clear();

            // If the client is connected.
            if (State.CONNECTED == state) {
                connectionService.disconnect(node);
            }

            // Un-subscribe.
            for (Subscription subscription : subscriptions) {
                subscription.unsubscribe();
            }
            // Disable the timer.
            connectionMonitorTask.cancel();

            // Send a disposed notification.
            stateSubject.onNext(State.DISPOSED);
            state = State.DISPOSED;

            // Complete the observables.
            stateSubject.onCompleted();
        } finally {
            monitor.leave();
        }
    }

    /**
     * Verifies the state of the VTEP client equals the expected state.
     * @param expected The expected state.
     * @throws VtepStateException Exception thrown if the current and expected
     * state are not equal.
     */
    @GuardedBy("monitor")
    private void assertState(VtepDataClient.State expected)
        throws VtepStateException {
        if (expected != state) {
            throw new VtepStateException(
                endPoint,
                "The VTEP data client state expected to be " + expected);
        }
    }

    /**
     * Verifies the state of the VTEP client equals the expected state.
     * @param expected The expected state.
     * @throws VtepStateException Exception thrown if the current and expected
     * state are not equal.
     */
    @GuardedBy("monitor")
    private void assertAnyState(State... expected)
        throws VtepStateException {
        for (VtepDataClient.State st : expected) {
            if (st == state) return;
        }
        throw new VtepStateException(
            endPoint, "The VTEP data client expected to be state " +
                      Arrays.deepToString(expected));
    }

    /**
     * A handler called when the client is connected.
     */
    @GuardedBy("monitor")
    private void onConnected(Connection connection) {
        monitor.enter();
        try {
            if (State.DISPOSED == state) {
                log.debug("Client already disposed, ignore.");
                return;
            }

            log.info("Connected to VTEP on {} with connection {}",
                     endPoint, connection.getIdentifier());

            // Get the VTEP physical switch.
            if (null != node) {
                physicalSwitch = getPhysicalSwitch(node);
            }

            state = State.CONNECTED;
            stateSubject.onNext(State.CONNECTED);

            onSuccessCallback();
        } finally {
            monitor.leave();
        }
    }

    /**
     * A handler called when the client is disconnected.
     */
    @GuardedBy("monitor")
    private void onDisconnected(Connection connection) {
        monitor.enter();
        try {
            if (State.DISPOSED == state) {
                log.debug("Client already disposed, ignore");
                onErrorCallback(
                    new VtepStateException(endPoint,
                                           "The connection is disposed."));
                return;
            }

            if (users.isEmpty()) {
                log.info("Disconnected from VTEP {} with connection {}",
                         endPoint, connection.getIdentifier());

                state = State.DISCONNECTED;
                stateSubject.onNext(State.DISCONNECTED);
            } else {
                log.warn("Connection to VTEP {} broken, attempting to "
                         + "reconnect at {}", endPoint,
                         connectionMonitorTask.scheduledExecutionTime());

                state = State.BROKEN;
                stateSubject.onNext(State.BROKEN);
            }
        } finally {
            monitor.leave();
        }
    }

    /**
     * A handler called when the client receives a monitor event.
     */
    @GuardedBy("monitor")
    private void onConnectionMonitor() {
        if (!monitor.enterIf(isBrokenOrUnused)) {
            return;
        }

        try {
            log.debug("Connection monitor event for VTEP {}", endPoint);
            if (State.DISPOSED == state) {
                return;
            }

            // If the connection is unused, and the connection expiration timer
            // reaches the maximum, dispose the connection
            if (users.isEmpty()) {
                if (++connectionExpirationCounter ==
                    MAX_IDLE_CONNECTION_MONITOR_INTERVALS) {
                    log.info("Connection for VTEP {} expired", endPoint);
                    dispose();
                    return;
                }
            } else {
                connectionExpirationCounter = 0;
            }

            // If the connection is broken, attempt to reconnect.
            if (State.BROKEN == state) {
                log.info("Attempting to reconnect to VTEP {}", endPoint);

                state = State.CONNECTING;
                stateSubject.onNext(State.CONNECTING);

                physicalSwitch = null;

                // Begin connecting to the VTEP. The connection service will
                // notify via the observable when the connection is established.
                node = connectionService.connect(connectionId.toString(),
                                                 params);
            }
        } catch (Exception e) {
            if (e.getClass().equals(ConnectException.class) ||
                e.getClass().equals(SocketException.class)) {
                log.error("Reconnecting to VTEP {} failed: {}", endPoint,
                          e.getMessage());
            } else {
                log.error("Unexpected error when connecting to VTEP {}: {}",
                          endPoint, e.getMessage(), e);
            }
            state = State.BROKEN;
            stateSubject.onNext(State.BROKEN);
        } finally {
            monitor.leave();
        }
    }

    /**
     * Calls the current connection callbacks' onSuccess() method.
     */
    private void onSuccessCallback() {
        while(!callbacks.isEmpty()) {
            CallbackSubscription subscription = callbacks.remove();
            if (!subscription.isUnsubscribed()) {
                subscription.callback.onSuccess(this);
            }
        }
    }

    /**
     * Calls the current connection callbacks' onError() method, with the
     * given exception.
     */
    private void onErrorCallback(VtepException e) {
        while(!callbacks.isEmpty()) {
            CallbackSubscription subscription = callbacks.remove();
            if (!subscription.isUnsubscribed()) {
                subscription.callback.onError(e);
            }
        }
    }

    /**
     * Gets the node for the current VTEP connection.
     * @return The node, or null if the VTEP is not connected.
     */
    @GuardedBy("monitor")
    @Nullable
    Node getConnectionNode() {
        return State.CONNECTED == state ? node : null;
    }

    /**
     * Gets the node for the current VTEP connection.
     * @return The node, never null.
     * @throws VtepNotConnectedException The VTEP is not connected.
     */
    @GuardedBy("monitor")
    @Nonnull
    Node getConnectionNodeOrThrow() throws VtepNotConnectedException {
        Node node = this.node;
        if (State.CONNECTED != state || null == node)
            throw new VtepNotConnectedException(endPoint);
        return node;
    }

    /**
     * Returns the OVSDB internal cache for the given table, if it doesn't
     * exist or it's empty, returns an empty map.
     *
     * @param node  The connection node.
     * @param tableName the requested table.
     * @return the cached contents, if any.
     */
    @Nullable
    Map<String, Table<?>> getTableCache(Node node, String tableName) {

        InventoryServiceInternal isi =
            connectionService.getInventoryServiceInternal();
        if (isi == null) {
            return null;
        }

        Map<String, ConcurrentMap<String, Table<?>>> cache = isi.getCache(node);
        if (cache == null) {
            return null;
        }

        Map<String, Table<?>> tableCache = cache.get(tableName);
        if (tableCache == null) {
            tableCache = new HashMap<>(0);
        }

        return tableCache;
    }

    /**
     * Returns the OVSDB internal cache for the given table, or
     * <code>null</code> if the table does not exist.
     *
     * @param tableName The name of requested table.
     * @return The cached contents, if any.
     */
    @Nullable
    Map<String, Table<?>> getTableCacheOrThrow(String tableName)
        throws VtepNotConnectedException {
        return getTableCache(getConnectionNodeOrThrow(), tableName);
    }

    /**
     * Gets the physical switch corresponding to the current VTEP.
     */
    @GuardedBy("monitor")
    @Nullable
    PhysicalSwitch getCurrentPhysicalSwitch() {
        return physicalSwitch;
    }

    /**
     * Gets the physical switch corresponding to the current VTEP.
     *
     * @param node The connection node.
     */
    @Nullable
    PhysicalSwitch getPhysicalSwitch(Node node) {
        Collection<PhysicalSwitch> psList = this.listPhysicalSwitches(node);
        String mgmtIp = endPoint.mgmtIp.toString();
        log.debug("Available physical switches for VTEP {}: {}",
                  endPoint, psList);
        for (PhysicalSwitch ps : psList) {
            if (ps.mgmtIps.contains(mgmtIp)) {
                return ps;
            }
        }
        log.warn("Physical switch for VTEP {} not found", endPoint);
        return null;
    }

    /**
     * Gets the list of the physical switches.
     * @param node The connection node.
     */
    @Nonnull
    List<PhysicalSwitch> listPhysicalSwitches(Node node) {
        Map<String, Table<?>> tableCache =
            getTableCache(node, Physical_Switch.NAME.getName());
        if (tableCache == null) {
            return new ArrayList<>();
        }
        List<PhysicalSwitch> res = new ArrayList<>(tableCache.size());
        for (Map.Entry<String, Table<?>> e : tableCache.entrySet()) {
            log.debug("Found Physical Switch {} {}", e.getKey(), e.getValue());
            Physical_Switch ovsdbPs = (Physical_Switch)e.getValue();
            res.add(VtepModelTranslator.toMido(
                ovsdbPs,
                new org.opendaylight.ovsdb.lib.notation.UUID(e.getKey())));
        }
        return res;
    }

    @Override
    public String toString() {
        return endPoint.toString();
    }
}
