# Merged Maps

## Overview
In MidoNet, ARP, MAC, and routing tables are distributed data structures. In
MidoNet v1.x, these are implemented using Replicated Maps, a class
using ZooKeeper as a backend where entries are encoded as directory paths.
With MidoNet v2, these tables are implemented using merged maps, a more scalable
alternative that are based on [Apache Kafka](http://kafka.apache.org), a
publish-subscribe middleware. Merged maps are modular by design and could
easily be adapted to any other publish-subscribe system.

A merged map offers a standard map interface but allows several values to be
associated to the same key. These values are called the opinions of the key,
where each opinion belongs to an owner (i.e., a MidoNet agent).
The merged map treats all writes as opinions and exposes the map for reads as
a consolidated view, where for each key, one opinion is deterministically
selected as the winning opinion using a conflict resolution strategy.

In the case of a MAC table for instance, the various opinions for a given MAC
(the map key) correspond to the ports two or more VMs are connected to with the
same MAC. Different opinions for the same MAC may appear when migrating a VM
from one machine to another.

## Architecture

A merged map relies on the publish-subscribe system to propagate map opinions.
Each map is associated to a topic (the map id is the topic's name) and new
opinions are published on the map's topic. We chose this design over one
where each owner has its own topic because the latter forces to consume
messages for merged maps that are of no interest to certain consumers.

In Kafka, a topic message can have
a key. In our case, the message key is a tuple (map key, owner) and the message 
payload is a tuple (map key, map value, owner). We explain below the role of the
message key. A message payload with a null value indicates an owner's deletion
of its opinion for the given key.

An instance of a merged map subscribes to the corresponding topic and
whenever a new opinion is received for some key, it determines whether this
opinion supersedes the previously winning opinion using the conflict resolution
strategy. If the just received opinion encodes an opinion removal, the same
process is performed to determine if the winning opinion for the given key
changes. Each instance of a merged map relies on a local cache that
contains, for each key, the opinions associated to this key as well as the
winning opinion.

## Availability & Durability

An opinion is deemed successfully published when it was successfully
acknowledges by a majority of the topic's replicas (the Kafka message log
is replicated on several brokers). By default, topics have 3
replicas and Kafka brokers asynchronously persist published messages to their
logs. Publishing opinions is blocked whenever the number of alive replicas falls
below a majority. This prevents loss of messages in the face of a minority of
failures despite asynchronous disk writes. This trades higher replication costs
for high publishing throughput while maintaining message durability.

Kafka's log is configured to perform regular log compaction. Log compaction
ensures that the latest kafka message is kept for each message key. Because a
message key is a tuple (map key, owner), we keep the latest opinion of each
owner for every map key. Consequently, MidoNet agents are always able to fully
reconstruct a merged map, even after a restart.

A Cluster Minion (1) is responsible to garbage-collect opinions of agents that
leave the system. A Kafka message is composed of a message key and value. To
remove all opinions of a given owner, the agent that left the system, we publish
a message with a null value and key (map key, owner) for all of its published
opinions. If the Minion dies, we use ZooKeeper's leader election capabilities
to select a new Cluster node to take over the task of garbage collecting
topic messages.

\(1\) [Cluster Minion](https://github.com/midonet/midonet/blob/master/specs/2015.02/cluster_design.md)