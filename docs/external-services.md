# Operating External Services

This document explains how to operate external services such as Zookeeper,
Cassandra, and Kafka.

## Zookeeper

TODO

## Cassandra

TODO

## Kafka

To configure the broker, edit the 'server.properties' file in the 'config'
subdirectory. At a minimum set the following properties:

* broker.id: The unique identifier of this broker.
* advertised.host.name: The IP address or hostname of the broker. This needs to
  be set so that clients can connect to your server.
* log.dirs: A comma separated list of directories where to store the logs.
* log.cleaner.enable: Set this value to true to enable log cleaning.
* controlled.shutdown.enable: Set this value to true to enable graceful shutdown
  when the broker is stopped. This will sync all logs to disk and migrate any
  topics this broker is a leader for to other replicas.
* zookeeper.connect: The list of Zookeeper hosts.
* auto.leader.rebalance.enable: Set this value to true to automatically trigger
  a balancing operation whenever a broker restarts. This ensures that the load
  of leadership for the various topics is shared among the live replicas.

A typical Midonet deployment contains three Kafka brokers and tolerates one
failure. It is possible to restore the original level of fault-tolerance after
a crash by either restarting the crashed broker or adding a new broker. A Kafka
broker that newly joins the system will only serve as replica for topics
created later on. It is thus important to reassign replicas of the existing
topics to the current set of replicas. This is done by resorting to command:

    bin/kafka-reassign-partitions.sh

More documentation on this command as well as Kafka in general can be consulted
at this [address](http://kafka.apache.org/documentation.html).