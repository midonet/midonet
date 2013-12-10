# Cassandra Cache

This document describes details relative to the Cassandra-based
implementation of the Cassandra cached used for connection tracking, and
NAT mappings.

## Client libraries

The current client library is Hector. The main complaints related to it
is the lack of support for asynchronous operations. Although this could
be easily implemented within Midolman, it'd be desirable to use a
library that has built in support for this.

We're also considering Astyanax (from Netflix) or the Datastax official
Java driver. Both bring async operations, along with good CQL support
and other features. The official driver may be advisable looking
towards an upgrade to the new Cassandra 2 version.

## Normal operation

Cassandra operations are all synchronous, and therefore there is a risk
that connectivity problems may have an impacton simulation latency.

## Failover

Midolman should be able to cope with the loss of connectivity to a
minority of the Cassandra nodes. Ideally, no packets would be lost in
this process.

As of 1.2.0, the current implementation presents some practical
limitations that make packet loss a possibility upon node failure. This
loss should be transient, only while the Hector client marks the node as
down and excludes it from the healthy node pool.

This is the process to execute an operation in Cassandra:

- The operation is requested from CassandraClient.
- CassandraClient forwards the request to the Hector library and waits
  for a result.
- If any exception happens, the operation will not be retried and fail
  immediately.

As is evident from this, we rely completely on Hector's failure
handling. Going more in detail on what happens inside the library:

### Hector: chosing a client

Hector is based on a [pool
architecture](http://hector-client.github.io/hector/build/html/content/poolArchitecture.html)

- Hector has a pool of hosts. Each of these have a pool of clients. We
  have a Round Robin policy to chose hosts from the pool.
  - The number of clients in each host's pool is determined by
    `max_active_connections`. It will start as this value / 3, as seen
    in ConcurrentHClientPool's constructor and borrowClient method.
- When an operation is executed,
  KeyspaceServiceImpl::operateWithFailover will attempt to execute the
  operation going to HConnectionManager::operateWithFailover
- This will enter a loop while not successful.
- It will try to fetch a Client pool from the Load Balancing policy (RR
  as noted above), using only hosts marked as available
- From the given pool, try to fetch a Client connection
- If a client is found, the thrift call is executed on the given host
  and we continue either with success, or exception handling.
- If a client is not found on the given host, Hector will fail.
    - Note that this policy is customizable and must be explicitly set
      in CassandraClient, or Hector will apply the default value which
      is BLOCK. Needless to say, this means that this would block a
      simulation thread for as long as a given host is down.

### Hector: handling a failed operation

The behaviour upon operation failure is determined by the
FailoverPolicy.

Failures in Cassandra will fall in two categories. In one, the Cassandra node
will be responsive, and fail the operation for some legitimate reason. For
example, if we have a write consistency level of 2, but the Cassandra node is
unable to write on 2 replicas, it will fire an error. 

The one we're concerned with here are losses of a Cassandra node (e.g.: due to
a network partition, node being down, long GC pauses...). In this case, the
request will timeout, throwing a HTimedOutException in the Hector code. This is
dealt with by in the following way (see
HConnectionManager::operateWithFailover).

Hector will evaluate the number of timeouts experienced in the same host within a time
window. This contains a counter defaulted to 10, the windows is 500ms. That is:
If after 10 timeouts, if the first one happened out of the window (that is,
more than 500 ms ago) the host will become suspended and never used by more
operations (see below for how the node recovery will be handled).

There are important implications here:

1. The client connection to a host is closed, but the host is not
   released from the host's client pool, so further requests may hit the
   same node, and fail, until enough timeouts cause the host to be
   suspended. Once a node is suspended, it won't be eligible for future
   operations.

2. On a failing node, clients are gradually removed from the hosts's
   pool, which whill shrink and eventually become exhausted (from the
   configured size of 3, as per our CassandraConfig.java:33). Therefore,
   if a node fails, Midolman will need to see see 3 timeouts within
   500ms before it runs out of available clients on this host.
   - When the client pool of a host not yet suspended is empty the
     ExhaustedPolicy comes into play. The next request that comes into
     Hector and falls on that host's client pool will find it empty. At
     that point, Hector may block, fail, or grow the pool.  Currently we
     chose FAIL, to avoid delaying simulations.

We'll discuss the implications on MM's performance below.

### Hector: handling failing nodes

The suspension of a host lasts 10 seconds, and there will be checks for
recovery every 10 s (all this is in HostTimeoutTracker.java). Once
connectivity is restored, it'll be returned to the healthy pool.

Downed hosts are also monitored, in case one of them is detected to be
back up it will be returned to the available list of hosts for future
operations.

### MM behaviour on node failure

As described, when a Cassandra node fails, the expected behaviour should
be the following. We assume there are is a constant flow of calls to
Cassandra during the period. All times are taken from a t=0 when the
connectivity to the node starts failing.

- Within 2.5 seconds (as determined by the `thrift_socket_timeout`
  config setting), an exception should appear in MM advising about a
  timeout. This will imply that one of the clients assigned to the pool
  becomes unavailable.

After this, there are two options:
- The host timeout tracking mechanism will detect more than
  `host_timeout_counter` timeouts in less than `host_timeout_window`
  milliseconds. This is the fastest option, but requires that many
  timeouts happen within a short period of time.
  - Note that this may be specially problematic for us since we try to
    go to Cassandra as little as possible and many packets will be
    hitting flows. Hitting the timeout tracker case requires higher
    traffic.
  - Increase the window size, or increase the counter, or both, to be
    more strict when diagnosing node failures.
- The pool of clients in the given host becomes exhausted. The time for
  this to happen will depend on how many clients are present in the
  host's pool. The max number of clients is determined by
  `max_active_connections`: each of them will have to timeout at least
  once to be closed and removed from the hosts's queue. This is the
  slowest case, it may take up to `max_active_connections *
  thrift_socket_timeout` seconds.

The following are the relevant parameters (to be set in a
CassandraHostConfigurator instance handed over to the
HFactory.getOrCreateCluster as done in the CassandraClient::connect
method). Those marked as "configurable" can be tuned in midolman.conf.

- setRetryDownedHosts and setRetryDownedHostsDelayInSeconds determine
  whether Hector should retry to reconnect to downed hosts, and the
  interval of these checks. These are set to true and 5s.
- setCassandraThriftSocketTimeout: this determines how long Hector will
  wait for a reply before timing out. Configurable.
- setHostTimeoutCounter and setHostTimeoutWindow: determine how many
  timeouts are acceptable in the given window. If this limit is
  surpassed, the host will be marked as suspended. These are only
  applied of setHostTimeoutTracker is enabled, which it is by default.
  All three are configurable.

