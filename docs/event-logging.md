# Event Logging (Experimental)

This work was started as a part of Zabbix integration work in the tools team.
Main goal is to be able to provide event logs so third party monitoring tools
like Zabbix can consume and monitor the system. Events include virtual topology
changes, status changes to the connection to external systems e.g. NSDB, BGP,
errors, etc, which MidoNet system administrator should be aware of.

## Overview

### Events

[Event Messages](https://docs.google.com/a/midokura.com/spreadsheet/ccc?key=0AuTxlBUkbgiUdEhNdFVkWkxUWGpIVzJXQlVEV0NxNkE&usp=drive_web#gid=0)
has a list of events that MidoNet system produces.

### Event log file

Events are visible to users and they are logged in a separate log file
as well as normal log file. By default, event log files will be located
at /var/log/tomcat*/midonet-api.event.log and
/var/log/midolman/midolman.event.log for API server and Agent respectively.

### Resource bundle file for event definition

Events are defined in resource bundle file EventMessage.property in midonet-util
package.
Take Router Creation in the virtual topology, the following is the definition
for the event:

```
org.midonet.event.topology.Router.CREATE.message = CREATE routerId={0}, data={1}.
org.midonet.event.topology.Router.CREATE.level = INFO
org.midonet.event.topology.Router.CREATE.explanation = Router with routerId={0} was created.
```

`message` defines the format string that is used to interpolate event data.
          this message should be written from the user's perspective.

`level` defines its event level

`explanation` defines the user level explanation of the event. Currently this
is not used in the code, but can be used for generating a message guide
document. Also this is useful for putting coders users' perspective.


### Event wrapper class

For each object that generates events, there is a wrapper class to handle events.
In that event class, there should be a method per event that takes in
parameters from the caller. The parameters will be interpolated to the format
string defined in the resource bundle file.
For example if you want to log an event for a router creation, here's what you
should do:

```
RouterEvent routerEvent = new RouterEvent();
routerEvent.create(routeId, routerData)
```

With the resource bundle file shown above, the following log will be recorded.

```
2014.03.21 00:38:56.447 tomoe-VirtualBox INFO  org.midonet.event.topology.Router - CREATE routerId=f198060f-7cab-423d-aab4-859db6e2f174, data=org.midonet.cluster.data.Router{id=f198060f-7cab-423d-aab4-859db6e2f174, data=Router.Data{name='sample-router', inboundFilter=null, outboundFilter=null, loadBalancer=null, properties={tenant_id=admin}, adminStateUp=true}}.

```

Here you can see that the format in the resource bundle is expanded with
parameters, and logged with INFO level as defined in the resource bundle.


### Limitations

* implementation of virtual topology change event logging
doesn't tell who does the operation. That will be one of the future works.

* Error related events have to be defined and properly generated more especially
  in Agent.

* Connection status change events for Cassandra and BGP haven't implemented.


## Future works

* support more events such as:
 * connection status change with NSDB, cache.
 * more events (especially error,and warnings) in Network Agent
* support auditing for API changes.
* extract resource bundle file as a config, instead of packaged in a jar at this monent,
  so it can be configured at deployment time
