# MidoNet Resource Protection

Resource protection is a feature that isolates traffic coming into a Midolman
instance. Traffic comes in through a datapath port, which has its own Netlink
channel. We throttle the traffic arriving from these channels so as to ensure
fair traffic processing for all ports. This also protects a tenant's VMs from
Denial of Service attacks coming from rogue VMs in the same host. Datapath ports
are assigned a token bucket, packets being allowed through only if tokens can be
retrieved from the bucket. Token buckets are linked hierarchically, in a tree
structure. Tokens are assigned to buckets fairly at each level of the hierarchy.
This means that the capacity for processing traffic is shared among (the ports
associated with) the buckets at a given level. If one port is idle or is sending
at a lower rate than it's maximum, that remaining share gets transferred over to
the other buckets at the same level in the hierarchy.

## The Hierarchical Token Bucket

The TokenBucket class implements the hierarchical token bucket. Starting from a
root bucket, whose size defines the total burst capacity of the system, it
allows a hierarchy of buckets to be created. Tokens are injected into the root
bucket according to the specified fill rate strategy and are distributed
recursively among the root's children. When all the buckets in a level of the
hierarchy are full, tokens accumulate in the parent buckets and ultimately at
the root. Tokens can only be retrieved from the leaf buckets. Assuming the
depicted hierarchy, retrieving tokens from one of the leaf buckets operates as
follows:

<pre>
            ┌───┐
            │   │ root
            └───┘
              ↑
           ___│___
          │       │
        ┌───┐   ┌───┐
 tunnel │   │   │   │ vms
        └───┘   └───┘
                  ↑
               ___│___
              │       │
            ┌───┐   ┌───┐
        vm0 │   │   │   │ vm1
            └───┘   └───┘
</pre>

 * We first try to consume the specified amount of tokens from the bucket, if
   it has the sufficient amount;

 * If it does not, we grab the existing tokens and then trigger a distribution
   at the root. We distribute the tokens obtained from the TokenBucketFillRate™
   and those accumulated at the root and at each intermediary bucket until there
   are no more tokens to give or all buckets are full;

 * At the tunnel bucket, we add half of the new tokens to its running account.
   At the VMs bucket, we distribute evenly to the leafs. If all buckets at a
   given level are full, we return excess tokens to the parent, which tries to
   accumulate those tokens. If the parent is also full, those additional tokens
   will potentially go to its siblings or up again to its parent. For example,
   if all the VM buckets become full and their parent still has tokens to
   distribute, it tries to add those excess tokens to its running account. If it
   doesn't have enough capacity, it returns those tokens to the root, which will
   try to give them to the tunnel bucket. If the root still gets tokens back,
   meaning that both children are full, it will either accumulate them or drop
   them depending on its capacity.

 * We do a fair distribution, distributing tokens one by one across a bucket's
   children. At each bucket we keep the index of the next child to get tokens,
   so that we maintain fairness over multiple distributions. We do only one
   distribution at any given time. Note, however, that buckets are being
   concurrently consumed from, so one bucket may not get as many tokens as the
   others at the same level if it was full for some of the distribution
   iterations;

 * After a distribution is attempted, we try again to consume tokens from the
   leaf. Note that we may have depleted the bucket and the distribution may have
   filled it up again, so we can obtain two or more full bursts. This is by
   design, as it only happens when other buckets are not sending at their
   maximum allowed capacity;

 * We execute a fixed point algorithm, meaning that we loop around until we
   obtain all the requested tokens or until we are unable to distribute new
   tokens.

## Token Injection

Our token injection strategy is to keep a fixed number of tokens in the system.
We consume a token on each packet-in and we return a token when we complete a
simulation (before the flow is created and the packet executed), when a
simulation is parked in the WaitingRoom and when new packets are made pending.
Every simulation thread writes the number of packets it executes in a private
counter. Later, a thread performing a distribution accumulates these counters
and distributes any new tokens that have been returned. This strategy means we
must be very zealous in returning every token we consume. It is implemented by
the TokenBucketSystemRate class.

## Configuration

We distinguish between three types of traffic: tunnel traffic, VTEP traffic and
normal VM traffic. Each type gives rise to one or more token buckets. A user
configures the burst capacity of each type of traffic, which will ultimately
configure the depth for each of these buckets.

The global amount of tokens, i.e., the global incoming burst capacity, can also
be configured, and it defines the total number of in-flight packets.

The ratio between the different types of traffic is configured by the shaping
the token bucket hierarchy and it's currently hard-coded by our linking policy.

## Linking Policy

We link token buckets in a hierarchy similar to the one depicted above. Under
the root bucket we link the tunnel bucket and the bucket that serves as a parent
of the VM buckets. This configuration allows tunneled traffic to get half of the
total capacity. At the same level we also link a bucket for VTEP traffic, if
applicable.

The VMs parent bucket has 0 capacity, meaning that it can't accumulate tokens:
if all individual VM buckets are full, excess tokens either go to the tunnel
bucket or into the root.

When we link a bucket we ensure that there are enough tokens in the system to
fill it by introducing the necessary amount of tokens into the hierarchy. This
way there are always enough tokens in the system to fill every bucket. This is
required because otherwise, if all tokens in the system were stored in inactive
buckets and we linked a new one, then it would not receive tokens as they
wouldn't be taken out of the token bucket hierarchy.

Upon unlinking a bucket, we again adjust the amount of tokens in the system by
decreasing the global capacity by the bucket's capacity. However, we don't let
the global capacity go below the user configured one. When the current global
capacity is less or equal to the configured one, we reintroduce into the
hierarchy all tokens stored in the bucket when we unlinked it.

The TokenBucketPolicy class embodies these policies.

## Token Multiplier

From an user's perspective, each token allows one packet in. However, we
internally apply a multiplier, currently set to 8, so that each token allows
more packets in. This is an optimization that enables us to retrieve a token
without employing an atomic instruction (necessary because of concurrent
distributions) and it also decreases the frequency of distributions. We divide
the user-specified capacity of each bucket by this multiplier in the
TokenBucketPolicy. We wrap the TokenBuckets in a Bucket class that encapsulates
and applies the multiplier. Consumers of that Bucket get 8 tokens for every one
retrieved from the underlying TokenBucket. TokenBucketSystemRate executes the
converse operation, dividing by 8 the number of packets that are returned to the
token bucket hierarchy.

The consumer of the Bucket class may not consume all of the multiplied tokens.
If a Bucket gets one token from the TokenBucket, thus having 8 tokens, but the
consumer only uses 2 tokens, then there will be 6 tokens stored in the Bucket
that need to be reintroduced into the hierarchy. In order to do so, there is
one extra counter shared by all Buckets. When a consumer is done consuming
tokens it signals that to the Bucket, which adds the unused tokens to that
counter, thus returning them. Note that this is performed atomically if the
one_to_one IO strategy is configured.

## Back Pressure

Currently, if we can't retrieve a token, we read the packet and discard it
immediately. This is considered a bug and there are plans to apply back pressure
through the kernel, letting packet accumulate there and avoiding superfluous
reads. Note that applying back pressure relies on the fact that all datapath
ports are assigned a dedicated Netlink channel.
