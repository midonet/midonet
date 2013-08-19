Midolman is an asynchronous program.  As multiple new flow notifications
arrive from the kernel, it can be operating on several of them at a time,
involving active computation and making requests to external services.
While those requests are outstanding, it's important that Midolman still
be able to make progress on the handling of other tasks, which requires
it to be written so as to support asynchronous operation.

The simplest way of doing asynchronous programming used in Midolman
is to make a synchronous call in a dedicated thread.  This has the
advantage that the code is straightforward and directly reflects the
data flow in the control flow.  However, we discourage programming this
way because threads are heavyweight, and coordinating shared state between
multiple threads is complicated and error prone.  So, we try to restrict
it to cases where there are no asynchronous interfaces for us to use instead.
For example, we use synchronous calls when querying Cassandra, because
we use Hector to handle automatic failover of Cassandra servers, and
Hector provides only synchronous methods.

Outside of the network simulation itself, the most common technique for
asynchronous programming is to use callbacks.  This works by passing
a callback object to the asynchronous call.  Conceptually, the next part 
of the computation consists of the callback's body.  The call will stash the
callback and immediately return with its task undone, but in progress or
scheduled or queued.  When the task completes, at some indefinite time,
the stashed callback object is recovered, and a specified method is called
on that object then with the result of the deferred task, conceptually 
resuming the delayed computation.  For example, the method

    public void fetchIntData(String key, Callback1<Integer> cb);

could be called as

    remoteDataStore.fetchIntData(someKey, new Callback1<Integer> {
        void call(Integer i) { /* Whatever we do with the integer fetched */ }
    });

A drawback of the callback technique is it requires the caller to be in
possession of the next segment of the computation, and chaining the callbacks
together can make the dataflow become obscured by layers of callbacks calling
callbacks calling callbacks.  An alternative which is mainly used in the
network simulation is to use futures, which abstractly represent an 
in-progress call.  Using this technique, our example method would be

    public Future<Integer> fetchIntData(String key);

The future can be passed around until it reaches code which knows what to do
when the call completes. Futures in Midolman generally come from two sources:
The `ask` method of Akka's `ActorRef` (and our `expiringAsk` wrapping method
around it); and the `Promise` class, which is a future which can be completed
with the `success` and `failure` methods.

Akka's Future is a Monad, meaning that we can perform an operation on the result
of a Future and chain futures by calling "map" with a lambda expression to be
applied on the result of the future. With future and a monadic map operation,
the above remote database call can be written as:

    def fetchIntData(key: String): Future[Int] = {
        val f = expiringAsk(dataStoreActor, FetchData(key), expiry)
        return f.mapTo[Data].map { data => convertToInt(data) }
    }

Often, the calls in the lambda will be asynchronous methods themselves,
which return futures.  In this case, you can use the "flatMap" shortcut
method on `Future`.  So if instead of returning `Int`, `convertToFutureInt`
returns a `Future[Int]`, we would write:

    def fetchIntData(key: String): Future[Int] = {
        val f = expiringAsk(dataStoreActor, FetchData(key), expiry)
        return f.mapTo[Data].flatMap { data => convertToFutureInt(data) }
    }

We can also write callbacks on a future with its `onComplete` method. Combined with Promise,
the above asynchronous call could be written like this in Scala, though it is more complex
than it needs to be and involves extra indirection.

    def fetchIntData(key: String): Future[Int] = {
        val p = new Promise[Int]
        val f = expiringAsk(dataStoreActor, FetchData(key), expiry)
        f.mapTo[Data].onComplete {
            case Right(data) =>
                p.success(convertToInt(data))
            case Left(error) =>
                p.failure(error)
        }
        return p
    }
        
We try to restrict as much as possible the mutable state shared between
actors.  The way we avoid updates to pieces of state used in a 
network simulation causing that simulation's state to be inconsistent
is through using read-copy-update (RCU).  When a simulation gets state
from an actor owning that state, it gets a snapshot of the state at the
time of request.  Any changes to the state thereafter aren't reflected
in the snapshot, so the simulation will run to completion as if the state
had been fixed.  This is done so we don't have to make the simulations
robust against state they use mutating during the simulation.  (For example,
we don't have to handle the case where a MAC becomes unlearned after we've
successfully looked it up, or a Chain's contents changing while we've
stepped half way through it.)
