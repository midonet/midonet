MidoNet REST API client library.

### Example Usage

Examples always tell you a lot more than text explanations.
Here, the code snippet below gives you an idea of how to do the following:

 * Instantiating a REST API session,
 * Creating a resource under its parent resource,
 * Getting resources under a parent resource,
 * Finding resources in a parent resource by an arbitrary field, and
 * Catching exceptions (right now exceptions are unchecked in line
   with jersey client's  UniformInterfaceException).


```java

    api = new MidonetApi("http://localhost:8080/midolmanj-mgmt/");
    api.enableLogging();

    Bridge b1 = api.addBridge().tenantId("tenant-1").name("bridge-1")
                        .create();
    Bridge b2 = api.addBridge().tenantId("tenant-1").name("bridge-2")
                        .create();


    MultivaluedMap qTenant1 = new MultivaluedMapImpl();
    qTenant1.add("tenant_id", "tenant-1");

    assertThat(api.getBridges(qTenant1).size(), is(2));
    for (Bridge b : api.getBridges(qTenant1)) {
        log.debug("BRIDGE: {}", b);
    }
    b2.delete();
    assertThat(api.getBridges(qTenant1).size(), is(1));



    try {
        Bridge b1 = api.addBridge().tenantId("tenant-2").name("bridge-1")
                .create();
        Bridge b2 = api.addBridge().tenantId("tenant-2").name("bridge-2")
                .create();
        b2 = b2.name("bridge-222").update();

    } catch (HttpException ex){
        log.debug("requests for bridges got an exception={}", ex.getError());
    }

    assertThat(api.getBridges("tenant_id=tenant-2").size(), is(2));
    b2.delete();
    assertThat(api.getBridges("tenant_id=tenant-2").size(), is(1));

```

### Design overview

There are basically 3 components in this client library, each of which
are described as follows:

*   com.midokura.midonet.client.MidonetApi

    This is the entry class for midonet client. You first should instantiate
    this class to manipulate resources in the MidoNet system.
    It has WebResource instance to make HTTP REST requests.

*   package com.midokura.midonet.client.dto;

    This package contains DTOs for MidoNet resources.
    These are wrapped by the following Resource packages and hidden from
    the client API.


* Resource

    This package contains hybrid of DTO/builder/DAO for wrapping MidoNet
    resources.
    A resource contains a DTO of its parent resource to get URI for creation.
    A resource has forwarding methods to retrieving data from its DTO(getters)
    and data accessor methods like create(), update(), get() which basically
    map to HTTP methods(with some exceptions like link() and unlink()).

    A resource also has parameter setter(builder) methods to build parameters
    before making REST API requests.


### Coding policy

Inside the resource file, methods should be grouped by types of operation
and ordered by the following:

1. delegate getters to the cached dto
2. setters to cached dto
3. getters of its parent resource
4. adders of its parent resources
5. data accessor (if any, e.g. link(), unlink())


### TODO

* Remove ResourceCollection.findBy() and replace it with find()
* Consider a better factories for Predicate class for ResourceCollection.find().
* Change midonet-api tests and functional tests to use this client.
