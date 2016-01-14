# NAT Leasing

To properly support SNAT, we need to ensure whichever NAT target an agent chooses
is unique in order to prevent distinct connections from conflicting. This
requires coordination between hosts and is carried out by the implementations of
NatBlockAllocator and NatLeaser.

## NatBlockAllocator

The NatBlockAllocator is responsible for allocating a unique block range for
a particular IP address associated with a virtual device. Each host is assigned
a block of 64 ports from which to locally assign ports to individual connections.
This ensures we don't need to coordinate between hosts for every SNAT rule we
encounter. Once a block is used up, the host can request more. If, on the other
hand, a block becomes unused, a host returns it to the global pool after a grace
period.

A block is randomly chosen from the set of unused NAT blocks to maximize
unpredictability. As blocks get allocated and freed or abandoned, we switch to an
LRU-based allocation algorithm. This mitigates two problems with the reservation
mechanism:

 * When a virtual port migrates to another host and we free the block, it can be
   picked up from yet another host. This host could allocate a port that conflicts
   with a migrated connection;
 * When a downed host recovers, it loads NAT keys that were previously allocated
   by it. If there are still active connections using those keys, they won't be
   released by that host. In the meanwhile, another host may have taken ownership
   of the NAT block that was used to give out those keys and it might generate
   conflicting ones.

By using LRU-based allocation, we further reduce the already low chances of
collisions arising.

We use ZooKeeper for coordination and the concrete ZkNatBlockAllocator carries
out the allocation. The directory structure is as follows:

 * `/nat`, a top level directory;
 * `/nat/{device}`, the device directory;
 * `/nat/{device}/{ip}`, the IP directory;
 * `/nat/{device}/{ip}/{block_index}`, the block directory, of which there are
   1024 (65536 / 64 = 1024), each corresponding to a block;
 * `/nat/{device}/{ip}/{block_index}/taken`, the ownership node, which is
   ephemeral.

To allocate a block, we check the list of children of the specified IP directory.
If this list doesn't contain all the paths of the blocks that are within the
specified range, then there are free blocks and we randomly choose one.
Otherwise, we load all block nodes for the range and, for each of these, we check
if it has children. If it doesn't, it means the block is free (the ephemeral node
was explicitly removed or the host is down). We use the [`Pzxid`](https://github.com/apache/zookeeper/blob/trunk/src/zookeeper.jute#L40) -
the `zxid` of the latest change to the children of that node - to order the nodes
according to the last time they were claimed and we choose the least recently
used one, namely the one with the lower `Pzxid`.

Freeing a block is trivially implemented by deleting the corresponding ownership
node.