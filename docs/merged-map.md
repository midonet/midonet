# Merged Maps

## Overview
In Midonet, ARP, MAC, and routing tables are distributed data structures. In
Midonet v1.x, these are implemented using Replicated Maps and Sets. With Midonet
v2, these tables are implemented using merged maps, a more scalable alternative
that are based on [Apache Kafka](http://kafka.apache.org), a publish-subscribe
middleware.

A merged map offers a standard map interface but allows several values to be
associated to the same key. These values are called opinions and each opinion
belongs to a specific owner, i.e., a Midonet agent. The merged map relies on a
conflict resolution strategy on map values to only expose winning opinions to
the outside world. In the case of a MAC table for instance, the various
opinions for a given MAC correspond to the ports two or more VMs are connected
to with the same MAC. Two different opinions for the same MAC may appear when
migrating a VM from one machine to another.

## Architecture

A merged map relies on Kafka to propagate map opinions. Each map is associated
with a Kafka topic (the map id is the topic's name) and new opinions are
published as Kafka messages with key (map key, owner) and message (map key, map
value, owner). An opinion with a null value indicates an owner's deletion of its
opinion for the given key.

An instance of a merged map subscribes to the corresponding Kafka topic and
whenever a new opinion is received for some key, it determines whether this
opinion supersedes the previously winning opinion using the conflict resolution
strategy. If the just received opinion encodes an opinion removal, the same
process is performed to determine if the winning opinion for the given key
has changed. Each instance of a merged map relies on a local cache that
contains, for each key, the opinions associated to this key as well as the
winning opinion.

## Availability & Durability

An opinion is deemed successfully published when it was successfully
acknowledges by a majority of the topic's replicas. By default, topics have 3
replicas and Kafka brokers asynchronously persist published messages to their
logs. Publishing opinions is blocked whenever the number of alive replicas falls
below a majority. This prevents loss of messages in the face of a minority of
failures despite asynchronous disk writes. This trades higher replication costs
for high publishing throughput while maintaining message durability.

Kafka's log is configured to perform regular log compaction. Log compaction
ensures that the latest kafka message is kept for each message key. Because a
message key is a tuple (map key, owner), we keep the latest opinion of each
owner for every map key. Consequently, Midonet agents are always able to fully
reconstruct a merged map even after a restart.

A minion is responsible to garbage-collect opinions of agents that leave the
system. A Kafka message is composed of a message key and value. To remove
all opinions of a given owner, the agent that left the system, we publish a
message with a null value and key (map key, owner) for all of its published
opinions.
