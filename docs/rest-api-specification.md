# MidoNet API Specification (caddo-12.12.1)

#### Table of Contents
[Introduction](#introduction)

[Getting Started](#getstarted)

[Common Behaviors](#commonbehaviors)
  * [Media Types](#mediatypes)
  * [Request Headers](#requestheaders)
  * [Response Headers](#responseheaders)
  * [HTTP Status Codes](#statuscodes)
  * [URI Templates](#uritemplates)

[Resource Models](#resourcemodels)
  * [Application](#application)
  * [Router](#router)
  * [Bridge](#bridge)
  * [Port](#port)
  * [Route](#route)
  * [Port Group](#portgroup)
  * [Chain](#chain)
  * [Rule](#rule)
  * [BGP](#bgp)
  * [Route Advertisement](#routeadvertisement)
  * [Host](#host)
  * [Interface](#interface)
  * [Host Command](#hostcommand)
  * [Host-Interface-Port Binding] (#hostinterfaceport)
  * [Tunnel Zone](#tunnelzone)
  * [Tunnel Zone Host](#tunnelzonehost)
  * [Metric Target](#metrictarget)
  * [Metric](#metric)
  * [Metric Query](#metricquery)
  * [Metric Query Response](#metricqueryresponse)
  * [Resource Collection](#resourcecollection)

[List of Acronyms](#acronyms)

<a name="introduction"/>
## Introduction

This document specifies a RESTful API for creating and managing MidoNet
resources.  The API uses JSON as its format.

<a name="getstarted"/>
## Getting Started

This section is intended to help users get started on using the API.  It assumes
that the MidoNet Management REST API host is known.  This host is represented
as ‘example.org’ in this document.  The following GET request to the base URL of
the API reveals the locations of the available resources.

    GET /
    Host: example.org
    Accept: application/vnd.com.midokura.midolman.mgmt.Application+json

The request above may yield the following output:


    HTTP/1.1 200 OK
    Content-Type: application/vnd.com.midokura.midolman.mgmt.Application+json
    {
        "uri": "http://example.org/",
        "version": "1",
        "bridges": "http://example.org/bridges",
        "chains": "http://example.org/chains",
        "hosts": "http://example.org/hosts",
        "portGroups": "http://example.org/port_groups",
        "routers": "http://example.org/routers",
        "bgpTemplate": "http://example.org/bgps/{id}",
        "adRouteTemplate": "http://example.org/ad_routes/{id}",
        "bridgeTemplate": "http://example.org/bridges/{id}",
        "chainTemplate": "http://example.org/chains/{id}",
        "hostTemplate": "http://example.org/hosts/{id}",
        "portTemplate": "http://example.org/ports/{id}",
        "portGroupTemplate": "http://example.org/port_groups/{id}",
        "routeTemplate": "http://example.org/routes/{id}",
        "routerTemplate": "http://example.org/routers/{id}",
        "ruleTemplate": "http://example.org/rules/{id}"
    }

This reveals that users can access the router resources using the URI
"/routers".  Host resources are accessible with the URI "/hosts".  The response
also includes information about the API version.  The URIs with "{id}" in them
are [URI templates](#uritemplates), and they are explained later in this
document.

<a name="commonbehaviors"/>
## Common Behaviors

This section specifies the common constraints that apply to all the requests
and responses that occur in the MidoNet Management REST API.

<a name="mediatypes"/>
### Media Types

In MidoNet REST API, the resources are encoded in JSON, as specified in
RFC 4267.  Each type of resource has its own media-type, which matches the
pattern:

*application/vnd.com.midokura.midolman.mgmt.xxxxx+json*

where “xxxxx“ represents the unique resource identifier.

<a name="requestheaders"/>
### Request Headers

The following HTTP request headers are relevant to MidoNet REST API:

<table>
    <tr>
        <th>Header</th>
        <th>Supported Values</th>
        <th>Description</th>
        <th>Required</th>
    </tr>
    <tr>
        <td>Accept</td>
        <td>Comma-delimited list of media types or media type patterns.</td>
        <td>Indicates to the server what media type(s) this client is prepared
         to accept.</td>
        <td>No, but recommended</td>
    </tr>
    <tr>
        <td>Content Type</td>
        <td>Media type describing the request message body.</td>
        <td>Describes the representation and syntax of the request message
        body.</td>
        <td>Yes</td>
    </tr>
</table>

<a name="responseheaders"/>
### Response Headers

The following HTTP response headers exist in MidoNet REST API:

<table>
    <tr>
        <th>Header</th>
        <th>Supported Values</th>
        <th>Description</th>
        <th>Required</th>
    </tr>
    <tr>
        <td>Content Type</td>
        <td>Media type describing the response message body.</td>
        <td>Describes the representation and syntax of the response message
        body.</td>
        <td>Yes</td>
    </tr>
    <tr>
        <td>Location</td>
        <td>Canonical URI of a newly created resource.</td>
        <td>A new URI that can be used to request a representation of the newly
         created resource.</td>
        <td>Yes, on response that create new server side resources accessible
         via a URI.</td>
    </tr>
</table>

<a name="statuscodes"/>
### HTTP Status Codes

The following HTTP status codes are returned from MidoNet REST API:

<table>
    <tr>
        <th>HTTP Status</th>
        <th>Description</th>
    </tr>
    <tr>
        <td>200 OK</td>
        <td>The request was successfully completed, and the response body
        contains the resource data.</td>
    </tr>
    <tr>
        <td>201 Created</td>
        <td>A new resource was successfully created.  A Location header
        contains the URI of the resource.</td>
    </tr>
    <tr>
        <td>204 No Content</td>
        <td>The server fulfilled the request, but does not need to return
        anything.</td>
    </tr>
    <tr>
        <td>400 Bad Request</td>
        <td>The request could not be processed because it contained missing
        or invalid information.</td>
    </tr>
    <tr>
        <td>401 Unauthorized</td>
        <td>The authentication credentials included with the request are
        missing or invalid.</td>
    </tr>
    <tr>
        <td>403 Forbidden</td>
        <td>The server recognized the credentials,
        but the user is not authorized to perform this request.</td>
    </tr>
    <tr>
        <td>404 Not Found</td>
        <td>The requested URI does not exist.</td>
    </tr>
    <tr>
        <td>405 Method Not Allowed</td>
        <td>The HTTP verb specified in the request
        (GET, POST, PUT, DELETE, HEAD) is not supported for this URI.</td>
    </tr>
    <tr>
        <td>406 Not Acceptable</td>
        <td>The resource identified by this request is not capable of
        generating a representation corresponding to one of the media types
        in the Accept header.</td>
    </tr>
    <tr>
        <td>409 Conflict</td>
        <td>A creation or update request could not be completed because it
        would cause a conflict in the current state of the resources. One
        example is when a request attempts to create a resource with an ID
        that already exists.</td>
    </tr>
    <tr>
        <td>500 Internal Server Error</td>
        <td>The server encountered an unexpected condition which prevented
        the request to be completed.</td>
    </tr>
    <tr>
        <td>503 Service Unavailable</td>
        <td>The server is currently unable to handle the request due to
        temporary overloading or maintenance of the server.</td>
    </tr>
</table>

<a name="uritemplates"/>
### URI Templates

A URI may contain a part that is left out to the client to fill.  These parts
are enclosed inside '{' and '}'.
</br>
For example, given a URI template, 'http://example.org/routers/{id}' and a
router ID 'd7435bb0-3bc8-11e2-81c1-0800200c9a66', after doing the replacement,
the final URI becomes:
'http://example.org/routers/d7435bb0-3bc8-11e2-81c1-0800200c9a66'.
</br></br>
The following table lists the existing expressions in the URI templates and
what they should be replaced with.

<table>
    <tr>
        <th>Expression</th>
        <th>Replace with</th>
    </tr>
    <tr>
        <td>id</td>
        <td>Unique identifier of resource.</td>
    </tr>
</table>

<a name="resourcemodels"/>
## Resource Models

This section specifies the representations of the MidoNet REST API resources.
Each type of resource has its own Internet Media Type. The media type for each
resource is included in square brackets in the corresponding section header.

The POST/PUT column indicates whether the field can be included in the request
with these verbs.  If they are not specified, the field should not be included
in the request.

<a name="application"/>
### Application [application/vnd.com.midokura.midolman.mgmt.Application+json]

This is the root object in MidoNet REST API.  From this object, clients can
traverse the URIs to discover all the available services.

<table>
    <tr>
        <th>Field Name</th>
        <th>Type</th>
        <th>POST/PUT</th>
        <th>Required</th>
        <th>Description</th>
    </tr>
    <tr>
        <td>uri</td>
        <td>URI</td>
        <td/>
        <td/>
        <td>A GET against this URI refreshes the representation of this
         resource.</td>
    </tr>
    <tr>
        <td>version</td>
        <td>Integer</td>
        <td></td>
        <td></td>
        <td>The version of MidoNet REST API.</td>
    </tr>
    <tr>
        <td>bridges</td>
        <td>URI</td>
        <td></td>
        <td></td>
        <td>A GET against this URI gets a list of bridges.</td>
    </tr>
    <tr>
        <td>chains</td>
        <td>URI</td>
        <td></td>
        <td></td>
        <td>A GET against this URI gets a list of chains.</td>
    </tr>
    <tr>
        <td>hosts</td>
        <td>URI</td>
        <td></td>
        <td></td>
        <td>A GET against this URI gets a list of hosts.</td>
    </tr>
    <tr>
        <td>portGroups</td>
        <td>URI</td>
        <td></td>
        <td></td>
        <td>A GET against this URI gets a list of port groups.</td>
    </tr>
    <tr>
        <td>routers</td>
        <td>URI</td>
        <td></td>
        <td></td>
        <td>A GET against this URI gets a list of routers.</td>
    </tr>
    <tr>
        <td>tunnelZones</td>
        <td>URI</td>
        <td></td>
        <td></td>
        <td>A GET against this URI gets a list of tunnel zones.</td>
    </tr>
    <tr>
        <td>adRouteTemplate</td>
        <td>String</td>
        <td></td>
        <td></td>
        <td>Template of the URI that represents the location of ad route with
        the provided ID.</td>
    </tr>
    <tr>
        <td>bgpTemplate</td>
        <td>String</td>
        <td></td>
        <td></td>
        <td>Template of the URI that represents the location of BGP with
        the provided ID.</td>
    </tr>
    <tr>
        <td>bridgeTemplate</td>
        <td>String</td>
        <td></td>
        <td></td>
        <td>Template of the URI that represents the location of bridge with
        the provided ID.</td>
    </tr>
    <tr>
        <td>chainTemplate</td>
        <td>String</td>
        <td></td>
        <td></td>
        <td>Template of the URI that represents the location of chain with
        the provided ID.</td>
    </tr>
    <tr>
        <td>hostTemplate</td>
        <td>String</td>
        <td></td>
        <td></td>
        <td>Template of the URI that represents the location of host with
        the provided ID.</td>
    </tr>
    <tr>
        <td>portTemplate</td>
        <td>String</td>
        <td></td>
        <td></td>
        <td>Template of the URI that represents the location of port with
        the provided ID.</td>
    </tr>
    <tr>
        <td>portGroupTemplate</td>
        <td>String</td>
        <td></td>
        <td></td>
        <td>Template of the URI that represents the location of port group with
        the provided ID.</td>
    </tr>
    <tr>
        <td>routeTemplate</td>
        <td>String</td>
        <td></td>
        <td></td>
        <td>Template of the URI that represents the location of route with the
        provided ID.</td>
    </tr>
    <tr>
        <td>routerTemplate</td>
        <td>String</td>
        <td></td>
        <td></td>
        <td>Template of the URI that represents the location of router with the
        provided ID.</td>
    </tr>
    <tr>
        <td>ruleTemplate</td>
        <td>String</td>
        <td></td>
        <td></td>
        <td>Template of the URI that represents the location of rule with the
        provided ID.</td>
    </tr>
    <tr>
        <td>tunnelZoneTemplate</td>
        <td>String</td>
        <td></td>
        <td></td>
        <td>Template of the URI that represents the location of tunnel zone
        with the provided ID.</td>
    </tr>
</table>

<a name="router"/>
### Router [application/vnd.com.midokura.midolman.mgmt.Router+json]

Router is an entity that represents a virtual router device in MidoNet. It
contains the following fields:

<table>
    <tr>
        <th>Field Name</th>
        <th>Type</th>
        <th>POST/PUT</th>
        <th>Required</th>
        <th>Description</th>
    </tr>
    <tr>
        <td>uri</td>
        <td>URI</td>
        <td/>
        <td/>
        <td>A GET against this URI refreshes the representation of this
         resource.</td>
    </tr>
    <tr>
        <td>id</td>
        <td>UUID</td>
        <td>POST</td>
        <td>No</td>
        <td>A unique identifier of the resource.  If this field is omitted in
         the POST request, a random UUID is generated.</td>
    </tr>
    <tr>
        <td>name</td>
        <td>String</td>
        <td>POST/PUT</td>
        <td>Yes</td>
        <td>Name of the router.  Must be unique within each tenant.</td>
    </tr>
    <tr>
        <td>tenantId</td>
        <td>String</td>
        <td/>
        <td/>
        <td>ID of the tenant that owns the router.</td>
    </tr>
    <tr>
        <td>ports</td>
        <td>URI</td>
        <td/>
        <td/>
        <td>A GET against this URI retrieves ports on this router.</td>
    </tr>
    <tr>
        <td>chains</td>
        <td>URI</td>
        <td/>
        <td/>
        <td>A GET against this URI retrieves the rule chains on this
         router.</td>
    </tr>
    <tr>
        <td>routes</td>
        <td>URI</td>
        <td/>
        <td/>
        <td>A GET against this URI retrieves the routes on this router.</td>
    </tr>
    <tr>
        <td>bridges</td>
        <td>URI</td>
        <td/>
        <td/>
        <td>A GET against this URI retrieves the bridges on this router.</td>
    </tr>
    <tr>
        <td>peerPorts</td>
        <td>URI</td>
        <td/>
        <td/>
        <td>A GET against this URI retrieves the logical ports attached to this
         router.</td>
    </tr>
    <tr>
        <td>inboundFilterId</td>
        <td>UUID</td>
        <td>POST/PUT</td>
        <td>No</td>
        <td>ID of the filter chain to be applied for incoming packets before
         routing.</td>
    </tr>
    <tr>
        <td>inboundFilter</td>
        <td>URI</td>
        <td/>
        <td/>
        <td>A GET against this URI retrieves the inbound filter chain.</td>
    </tr>
    <tr>
        <td>outboundFilterId</td>
        <td>UUID</td>
        <td>POST/PUT</td>
        <td>No</td>
        <td>ID of the filter chain to be applied for outgoing packets after
         routing.</td>
    </tr>
    <tr>
        <td>outboundFilter</td>
        <td>URI</td>
        <td/>
        <td/>
        <td>A GET against this URI retreives the outbound filter chain.</td>
    </tr>
</table>

<a name="bridge"/>
### Bridge [application/vnd.com.midokura.midolman.mgmt.Bridge+json]

Bridge is an entity that represents a virtual bridge device in MidoNet. It
contains the following fields:

<table>
    <tr>
        <th>Field Name</th>
        <th>Type</th>
        <th>POST/PUT</th>
        <th>Required</th>
        <th>Description</th>
    </tr>
    <tr>
        <td>uri</td>
        <td>URI</td>
        <td/>
        <td/>
        <td>A GET against this URI refreshes the representation of this
         resource.</td>
    </tr>
    <tr>
        <td>id</td>
        <td>UUID</td>
        <td>POST</td>
        <td>No</td>
        <td>A unique identifier of the resource.  If this field is omitted in
         the POST request, a random UUID is generated.</td>
    </tr>
    <tr>
        <td>name</td>
        <td>String</td>
        <td>POST/PUT</td>
        <td>Yes</td>
        <td>Name of the bridge.  Must be unique within each tenant.</td>
    </tr>
    <tr>
        <td>tenantId</td>
        <td>String</td>
        <td/>
        <td/>
        <td>ID of the tenant that owns the bridge.</td>
    </tr>
    <tr>
        <td>ports</td>
        <td>URI</td>
        <td/>
        <td/>
        <td>A GET against this URI retrieves ports on this bridge.</td>
    </tr>
    <tr>
        <td>dhcpSubnets</td>
        <td>URI</td>
        <td/>
        <td/>
        <td>A GET against this URI retrieves dhcpSubnets on this bridge.</td>
    </tr>
    <tr>
        <td>routers</td>
        <td>URI</td>
        <td/>
        <td/>
        <td>A GET against this URI retrieves routers on this bridge.</td>
    </tr>
    <tr>
        <td>filteringDb</td>
        <td>URI</td>
        <td/>
        <td/>
        <td>A GET against this URI retrieves filteringDb on this bridge.</td>
    </tr>
    <tr>
        <td>peerPorts</td>
        <td>URI</td>
        <td/>
        <td/>
        <td>A GET against this URI retrieves the logical ports attached to this
         bridge.</td>
    </tr>
    <tr>
        <td>inboundFilterId</td>
        <td>UUID</td>
        <td>POST/PUT</td>
        <td>No</td>
        <td>ID of the filter chain to be applied for incoming packes.</td>
    </tr>
    <tr>
        <td>inboundFilter</td>
        <td>URI</td>
        <td/>
        <td/>
        <td>A GET against this URI retrieves the inbound filter chain.</td>
    </tr>
    <tr>
        <td>outboundFilterId</td>
        <td>UUID</td>
        <td>POST/PUT</td>
        <td>No</td>
        <td>ID of the filter chain to be applied for outgoing packets.</td>
    </tr>
    <tr>
        <td>outboundFilter</td>
        <td>URI</td>
        <td/>
        <td/>
        <td>A GET against this URI retreives the outbound filter chain.</td>
    </tr>
</table>

<a name="port"/>
### Port [application/vnd.com.midokura.midolman.mgmt.Port+json]

Port is an entity that represents a port on a virtual device (bridge or router)
in MidoNet.  It contains the following fields:

<table>
    <tr>
        <th>Field Name</th>
        <th>Type</th>
        <th>POST/PUT</th>
        <th>Required</th>
        <th>Description</th>
    </tr>
    <tr>
        <td>uri</td>
        <td>URI</td>
        <td/>
        <td/>
        <td>A GET against this URI refreshes the representation of this
         resource.</td>
    </tr>
    <tr>
        <td>id</td>
        <td>UUID</td>
        <td/>
        <td/>
        <td>A unique identifier of the resource.</td>
    </tr>
    <tr>
        <td>deviceId</td>
        <td>UUID</td>
        <td/>
        <td/>
        <td>ID of the device (bridge or router) that this port belongs to.</td>
    </tr>
    <tr>
        <td>device</td>
        <td>URI</td>
        <td/>
        <td/>
        <td>A GET against this URI retrieves the device resource that the port
         belongs to.  If the port is a router port, it gets a router resource,
          and if it’s a bridge port, it gets a bridge resource.</td>
    </tr>
    <tr>
        <td>type</td>
        <td>String</td>
        <td>POST</td>
        <td>Yes</td>
        <td>Type of router port.  It must be one of:
<ul>
<li>MaterializedRouter</li>
<li>LogicalRouter</li>
<li>MaterializedBridge</li>
<li>LogicalBridge</li>
</ul>
<p>
Materialized router port is a virtual port that plugs into the VIF of an entity,
 such as a VM. It can also be a virtual port connected to a host physical port,
  directly or after implementing tunnel encapsulation. Access to materialized
   ports is managed by OpenVSwitch (OpenFlow switch).  Materialized bridge port
    is the same as materialized router port but it is a port on a virtual
     bridge.
</p>
<p>
Logical router port is a virtual port that only exists in the MidoNet virtual
 router network abstraction. It refers to a logical connection to another
  virtual networking device such as another router.  Logical bridge is the
   equivalent port type on a virtual bridge.
</p>
        </span></td>
    </tr>
    <tr>
        <td>peerId
(Logical only)</td>
        <td>UUID</td>
        <td/>
        <td/>
        <td>ID of the peer port that this port is linked to.</td>
    </tr>
    <tr>
        <td>peer
(Logical only)</td>
        <td>URI</td>
        <td/>
        <td/>
        <td>A GET against this URI retrieves the peer port resource.</td>
    </tr>
    <tr>
        <td>networkAddress
(Router only)</td>
        <td>String</td>
        <td>POST</td>
        <td>Yes</td>
        <td>IP address of the network attached to this port. For example
         192.168.10.32/27</td>
    </tr>
    <tr>
        <td>networkLength
(Router only)</td>
        <td>Int</td>
        <td>POST</td>
        <td>Yes</td>
        <td>Prefix length of the network attached to this port (number of
        fixed network bits).</td>
    </tr>
    <tr>
        <td>portAddress
(Router only)</td>
        <td>String</td>
        <td>POST</td>
        <td>Yes</td>
        <td>IP address assigned to the port.</td>
    </tr>
    <tr>
        <td>vifId
(Materialized only)</td>
        <td>UUID</td>
        <td/>
        <td/>
        <td>ID of the VIF plugged into the port.</td>
    </tr>
    <tr>
        <td>bgps
(Materialized router only)</td>
        <td>URI</td>
        <td/>
        <td/>
        <td>A GET against this BGP configurations for this port.</td>
    </tr>
    <tr>
        <td>vpns
(Materialized router only)</td>
        <td>URI</td>
        <td/>
        <td/>
        <td>A GET against this URI retrieves the VPN configurations for this
         port.</td>
    </tr>
    <tr>
        <td>link
(Logical only)</td>
        <td>URI</td>
        <td/>
        <td/>
        <td>POST against this URI links two logical ports.  In the body of the
         request, ‘peerId’ must be specified to indicate the peer logical port
          ID.  Setting ‘peerId’ to null effectively unlinks the ports.</td>
    </tr>
    <tr>
        <td>inboundFilterId</td>
        <td>UUID</td>
        <td>POST/PUT</td>
        <td>No</td>
        <td>ID of the filter chain to be applied for incoming packets.</td>
    </tr>
    <tr>
        <td>inboundFilter</td>
        <td>URI</td>
        <td/>
        <td/>
        <td>A GET against this URI retrieves the inbound filter chain.</td>
    </tr>
    <tr>
        <td>outboundFilterId</td>
        <td>UUID</td>
        <td>POST/PUT</td>
        <td>No</td>
        <td>ID of the filter chain to be applied for outgoing packets.</td>
    </tr>
    <tr>
        <td>outboundFilter</td>
        <td>URI</td>
        <td/>
        <td/>
        <td>A GET against this URI retrieves the outbound filter chain.</td>
    </tr>
</table>

<a name="route"/>
### Route [application/vnd.com.midokura.midolman.mgmt.Route+json]

Route is an entity that represents a route on a virtual router in MidoNet.  It
contains the following fields:

<table>
    <tr>
        <th>Field Name</th>
        <th>Type</th>
        <th>POST/PUT</th>
        <th>Required</th>
        <th>Description</th>
    </tr>
    <tr>
        <td>uri</td>
        <td>URI</td>
        <td/>
        <td/>
        <td>A GET against this URI refreshes the representation of this
         resource.</td>
    </tr>
    <tr>
        <td>id</td>
        <td>UUID</td>
        <td/>
        <td/>
        <td>A unique identifier of the resource.</td>
    </tr>
    <tr>
        <td>routerId</td>
        <td>UUID</td>
        <td/>
        <td/>
        <td>ID of the router that this route belongs to.</td>
    </tr>
    <tr>
        <td>router</td>
        <td>URI</td>
        <td/>
        <td/>
        <td>A GET against this URI gets the router resource.</td>
    </tr>
    <tr>
        <td>type</td>
        <td>String</td>
        <td>POST</td>
        <td>Yes</td>
        <td>Type of route:
<ul>
<li>Normal: Regular traffic forwarding route.</li>
<li>Reject: Drop packets and send ICMP packets back.</li>
<li>BlackHole: Drop packets and do not send ICMP packets back.</li>
</ul>
        </td>
    </tr>
    <tr>
        <td>srcNetworkAddr</td>
        <td>String</td>
        <td>POST</td>
        <td>Yes</td>
        <td>Source IP address.</td>
    </tr>
    <tr>
        <td>srcNetworkLength</td>
        <td>Int</td>
        <td>POST</td>
        <td>Yes</td>
        <td>Source network IP address length.</td>
    </tr>
    <tr>
        <td>dstNetworkAddr</td>
        <td>String</td>
        <td>POST</td>
        <td>Yes</td>
        <td>Destination IP address.</td>
    </tr>
    <tr>
        <td>dstNetworkLength</td>
        <td>Int</td>
        <td>POST</td>
        <td>Yes</td>
        <td>Destination network IP address length.</td>
    </tr>
    <tr>
        <td>weight</td>
        <td>Int</td>
        <td>POST</td>
        <td>Yes</td>
        <td>The priority weight of the route.</td>
    </tr>
    <tr>
        <td>nextHopPort
(Normal type only)</td>
        <td>UUID</td>
        <td>POST</td>
        <td>Yes</td>
        <td>The ID of the next hop port.</td>
    </tr>
    <tr>
        <td>nextHopGateway
(Normal type only)</td>
        <td>String</td>
        <td>POST</td>
        <td>Yes</td>
        <td>IP address of the gateway router to forward the traffic to.</td>
    </tr>
</table>

<a name="portgroup"/>
### PortGroup [application/vnd.com.midokura.midolman.mgmt.PortGroup+json]

<table>
    <tr>
        <th>Field Name</th>
        <th>Type</th>
        <th>POST/PUT</th>
        <th>Required</th>
        <th>Description</th>
    </tr>
    <tr>
        <td>uri</td>
        <td>URI</td>
        <td/>
        <td/>
        <td>A GET against this URI refreshes the representation of this
         resource.</td>
    </tr>
    <tr>
        <td>id</td>
        <td>UUID</td>
        <td/>
        <td/>
        <td>A unique identifier of the resource.</td>
    </tr>
    <tr>
        <td>tenantId</td>
        <td>UUID</td>
        <td/>
        <td/>
        <td>ID of the tenant that this chain belongs to.</td>
    </tr>
    <tr>
        <td>name</td>
        <td>String</td>
        <td>POST</td>
        <td>Yes</td>
        <td>Name of the port group.  Unique per tenant.</td>
    </tr>
</table>

<a name="chain"/>
### Chain [application/vnd.com.midokura.midolman.mgmt.Chain+json]

Chain is an entity that represents a rule chain on a virtual router in MidoNet.
It contains the following fields:

<table>
    <tr>
        <th>Field Name</th>
        <th>Type</th>
        <th>POST/PUT</th>
        <th>Required</th>
        <th>Description</th>
    </tr>
    <tr>
        <td>uri</td>
        <td>URI</td>
        <td/>
        <td/>
        <td>A GET against this URI refreshes the representation of this
         resource.</td>
    </tr>
    <tr>
        <td>id</td>
        <td>UUID</td>
        <td/>
        <td/>
        <td>A unique identifier of the resource.</td>
    </tr>
    <tr>
        <td>tenantId</td>
        <td>UUID</td>
        <td/>
        <td/>
        <td>ID of the tenant that this chain belongs to.</td>
    </tr>
    <tr>
        <td>name</td>
        <td>String</td>
        <td>POST</td>
        <td>Yes</td>
        <td>Name of the chain.  Unique per tenant.</td>
    </tr>
    <tr>
        <td>rules</td>
        <td>URI</td>
        <td/>
        <td/>
        <td>A GET against this URI retrieves the representation of the rules
         set for this chain.</td>
    </tr>
</table>

<a name="rule"/>
### Rule [application/vnd.com.midokura.midolman.mgmt.Rule+json]

Rule is an entity that represents a rule on a virtual router chain in MidoNet.
It contains the following fields:

<table>
    <tr>
        <th>Field Name</th>
        <th>Type</th>
        <th>POST/PUT</th>
        <th>Required</th>
        <th>Description</th>
    </tr>
    <tr>
        <td>uri</td>
        <td>URI</td>
        <td/>
        <td/>
        <td>A GET against this URI refreshes the representation of this
         resource.</td>
    </tr>
    <tr>
        <td>id</td>
        <td>UUID</td>
        <td/>
        <td/>
        <td>A unique identifier of the resource.</td>
    </tr>
    <tr>
        <td>chainId</td>
        <td>UUID</td>
        <td/>
        <td/>
        <td>ID of the chain that this chain belongs to.</td>
    </tr>
    <tr>
        <td>condInvert</td>
        <td>Bool</td>
        <td>POST</td>
        <td>No</td>
        <td>Invert the conjunction of all the other predicates.</td>
    </tr>
    <tr>
        <td>inPorts</td>
        <td>UUID</td>
        <td>POST</td>
        <td>No</td>
        <td>The list of (logical or materialized) ingress port UUIDs to
         match.</td>
    </tr>
    <tr>
        <td>invInPorts</td>
        <td>Bool</td>
        <td>POST</td>
        <td>No</td>
        <td>Inverts the in_ports predicate. Match if the packet's ingress is
         NOT in in_ports.</td>
    </tr>
    <tr>
        <td>outPorts</td>
        <td>Array of UUID</td>
        <td>POST</td>
        <td>No</td>
        <td>The list of (logical or materialized) egress port UUIDs to match.
        </td>
    </tr>
    <tr>
        <td>invOutPorts</td>
        <td>Bool</td>
        <td>POST</td>
        <td>No</td>
        <td>Inverts the out_ports predicate. Match if the packet’s egress is
        NOT in out_ports.</td>
    </tr>
    <tr>
        <td>nwTos</td>
        <td>Int</td>
        <td>POST</td>
        <td>No</td>
        <td>The value of the IP packet TOS field to match (0-255).</td>
    </tr>
    <tr>
        <td>invNwTos</td>
        <td>Bool</td>
        <td>POST</td>
        <td>No</td>
        <td>Invert the nwTos predicate. Match if the packet's protocol number
         is not nwTos.</td>
    </tr>
    <tr>
        <td>nwProto</td>
        <td>Int</td>
        <td>POST</td>
        <td>No</td>
        <td>The Network protocol number to match (0-255).</td>
    </tr>
    <tr>
        <td>invNwProto</td>
        <td>Bool</td>
        <td>POST</td>
        <td>No</td>
        <td>Invert the nwProto predicate. Match if the packet's protocol number
         is not nwProto.</td>
    </tr>
    <tr>
        <td>nwSrcAddress</td>
        <td>String</td>
        <td>POST</td>
        <td>No</td>
        <td>The IP address of the IP source prefix to match.</td>
    </tr>
    <tr>
        <td>nwSrcLength</td>
        <td>Int</td>
        <td>POST</td>
        <td>No</td>
        <td>The length of the source IP prefix to match (number of fixed
         network bits).</td>
    </tr>
    <tr>
        <td>invNwSrc</td>
        <td>Bool</td>
        <td>POST</td>
        <td>No</td>
        <td>Invert the IP source prefix predicate. Match packets whose source
         is NOT in the prefix.</td>
    </tr>
    <tr>
        <td>nwDstAddress</td>
        <td>String</td>
        <td>POST</td>
        <td>No</td>
        <td>The address part of the IP destination prefix to match.</td>
    </tr>
    <tr>
        <td>nwDstLength</td>
        <td>Int</td>
        <td>POST</td>
        <td>No</td>
        <td>The length of the IP destination prefix to match.</td>
    </tr>
    <tr>
        <td>invNwDst</td>
        <td>Bool</td>
        <td>POST</td>
        <td>No</td>
        <td>Invert the IP dest prefix predicate. Match packets whose
         destination is NOT in the prefix.</td>
    </tr>
    <tr>
        <td>tpSrcStart</td>
        <td>Int</td>
        <td>POST</td>
        <td>No</td>
        <td>The beginning of the tcp/udp source port range to match.  This
         field is required if invTpEnd is set.</td>
    </tr>
    <tr>
        <td>tpSrcEnd</td>
        <td>Int</td>
        <td>POST</td>
        <td>No</td>
        <td>The end of the tcp/udp source port range to match. This field is
         required if tpSrcStart is set.</td>
    </tr>
    <tr>
        <td>invTpSrc</td>
        <td>Bool</td>
        <td>POST</td>
        <td>No</td>
        <td>Invert the source tcp/udp port range predicate. Match packets whose
         source port is NOT in the range.</td>
    </tr>
    <tr>
        <td>tpDstStart</td>
        <td>Int</td>
        <td>POST</td>
        <td>No</td>
        <td>The beginning of the tcp/udp dest port range to match. This field
         is required if tpDstEnd is set.</td>
    </tr>
    <tr>
        <td>tpDstEnd</td>
        <td>Int</td>
        <td>POST</td>
        <td>No</td>
        <td>The end of the tcp/udp port range to match.  This field is required
         if tpDstStart is set.</td>
    </tr>
    <tr>
        <td>invTpDst</td>
        <td>Bool</td>
        <td>POST</td>
        <td>No</td>
        <td>Invert the destination tcp/udp port range predicate. Match packets
         whose dest port is NOT in the range.</td>
    </tr>
    <tr>
        <td>type</td>
        <td>String</td>
        <td>POST</td>
        <td>Yes</td>
        <td>Must be one of these strings: accept, dnat, drop, jump, rev_dnat,
         rev_snat, reject, return, snat.</td>
    </tr>
    <tr>
        <td>jumpChainId</td>
        <td>UUID</td>
        <td>POST</td>
        <td>No</td>
        <td>ID of the jump chain.  If the type == jump then this field is
         required.</td>
    </tr>
    <tr>
        <td>jumpChainName</td>
        <td>String</td>
        <td/>
        <td/>
        <td>Name of the jump chain.</td>
    </tr>
    <tr>
        <td>flowAction</td>
        <td>String</td>
        <td>POST</td>
        <td>Yes</td>
        <td>Action to take on each flow.  Must be one of accept, continue,
         return.</td>
    </tr>
    <tr>
        <td>natTargets</td>
        <td>Multi Array</td>
        <td>POST</td>
        <td>No</td>
        <td>list of nat_target. Each nat target is a (address-range,
         port-range) pair. An address-range is like ['1.2.3.4', '5.6.7.8'], a
          port-range is like [10, 11].  This field is required if the type is
           dnat or snat.</td>
    </tr>
    <tr>
        <td>position</td>
        <td>Int</td>
        <td>POST</td>
        <td>No</td>
        <td>The position at which this rule should be inserted &gt;= 1 and
         &lt;= the greatest position in the chain + 1.  If not specified, it is
          assumed to be 1.</td>
    </tr>
</table>

<a name="bgp"/>
### BGP [application/vnd.com.midokura.midolman.mgmt.Bgp+json]

BGP is an entity that represents a single set of BGP configurations. It
contains the following fields:

<table>
    <tr>
        <th>Field Name</th>
        <th>Type</th>
        <th>POST/PUT</th>
        <th>Required</th>
        <th>Description</th>
    </tr>
    <tr>
        <td>uri</td>
        <td>URI</td>
        <td/>
        <td/>
        <td>A GET against this URI refreshes the representation of this
         resource.</td>
    </tr>
    <tr>
        <td>id</td>
        <td>UUID</td>
        <td/>
        <td/>
        <td>A unique identifier of the resource.</td>
    </tr>
    <tr>
        <td>portId</td>
        <td>UUID</td>
        <td/>
        <td/>
        <td>ID of the port to set the BGP confgurations on.</td>
    </tr>
    <tr>
        <td>port</td>
        <td>URI</td>
        <td/>
        <td/>
        <td>A GET against this URI gets the port resource.</td>
    </tr>
    <tr>
        <td>localAS</td>
        <td>Int</td>
        <td>POST</td>
        <td>Yes</td>
        <td>Local AS number.</td>
    </tr>
    <tr>
        <td>peerAS</td>
        <td>Int</td>
        <td>POST</td>
        <td>Yes</td>
        <td>Peer BGP speaker’s AS number.</td>
    </tr>
    <tr>
        <td>peerAddr</td>
        <td>String</td>
        <td>POST</td>
        <td>Yes</td>
        <td>The address of the peer to connect to.</td>
    </tr>
    <tr>
        <td>adRoutes</td>
        <td>URI</td>
        <td/>
        <td/>
        <td>A GET against this URi retrieves the advertised routes of this BGP
         speaker.</td>
    </tr>
</table>

<a name="routeadvertisement"/>
### Route Advertisement [application/vnd.com.midokura.midolman.mgmt.AdRoute+json]

Advertised Route is an entity that represents an advertising route of BGP.  It
contains the following fields:

<table>
    <tr>
        <th>Field Name</th>
        <th>Type</th>
        <th>POST/PUT</th>
        <th>Required</th>
        <th>Description</th>
    </tr>
    <tr>
        <td>uri</td>
        <td>URI</td>
        <td/>
        <td/>
        <td>A GET against this URI refreshes the representation of this
         resource.</td>
    </tr>
    <tr>
        <td>id</td>
        <td>UUID</td>
        <td/>
        <td/>
        <td>A unique identifier of the resource.</td>
    </tr>
    <tr>
        <td>bgpId</td>
        <td>UUID</td>
        <td/>
        <td/>
        <td>ID of the BGP that the route belongs to.</td>
    </tr>
    <tr>
        <td>bgp</td>
        <td>URI</td>
        <td/>
        <td/>
        <td>A GET agains this URI gets the BGP resource.</td>
    </tr>
    <tr>
        <td>nwPrefix</td>
        <td>String</td>
        <td>POST</td>
        <td>Yes</td>
        <td>The prefix address of the advertising route.</td>
    </tr>
    <tr>
        <td>prefixLength</td>
        <td>Int</td>
        <td>POST</td>
        <td>Yes</td>
        <td>The prefix length of the advertising route.</td>
    </tr>
</table>

### VPN [application/vnd.com.midokura.midolman.mgmt.Vpn+json]
*This is NOT supported in Caddo.*

VPN is an entity that represents a single set of VPN configurations.  It
contains the following fields:

<table>
    <tr>
        <th>Field Name</th>
        <th>Type</th>
        <th>POST/PUT</th>
        <th>Required</th>
        <th>Description</th>
    </tr>
    <tr>
        <td>uri</td>
        <td>URI</td>
        <td/>
        <td/>
        <td>A GET against this URI refreshes the representation of this
         resource.</td>
    </tr>
    <tr>
        <td>id</td>
        <td>UUID</td>
        <td/>
        <td/>
        <td>A unique identifier of the resource.</td>
    </tr>
    <tr>
        <td>portId</td>
        <td>UUID</td>
        <td/>
        <td/>
        <td>ID of the port to set the VPN confgurations on.</td>
    </tr>
    <tr>
        <td>port</td>
        <td>URI</td>
        <td/>
        <td/>
        <td>A GET against this URI gets the port resource.</td>
    </tr>
</table>

<a name="host"/>
### Host [application/vnd.com.midokura.midolman.mgmt.Host+json]

Host is an entity that provides some information about a cluster node. It
contains the following fields:

<table>
    <tr>
        <th>Field Name</th>
        <th>Type</th>
        <th>POST/PUT</th>
        <th>Required</th>
        <th>Description</th>
    </tr>
    <tr>
        <td>uri</td>
        <td>URI</td>
        <td/>
        <td/>
        <td>A GET against this URI refreshes the representation of this
         resource.</td>
    </tr>
    <tr>
        <td>id</td>
        <td>UUID</td>
        <td/>
        <td/>
        <td>A unique identifier of the resource. It is usually autogenerated by
         the daemon running on the host.</td>
    </tr>
    <tr>
        <td>name</td>
        <td>String</td>
        <td/>
        <td/>
        <td>The last seen host name</td>
    </tr>
    <tr>
        <td>alive</td>
        <td>bool</td>
        <td/>
        <td/>
        <td>If the node-agent running on the host is connected to ZK.</td>
    </tr>
    <tr>
        <td>addresses</td>
        <td>MultiArray</td>
        <td/>
        <td/>
        <td>The of last seen ip addresses visible on the host</td>
    </tr>
</table>

<a name="interface"/>
### Interface [application/vnd.com.midokura.midolman.mgmt.Interface+json]

The interface is an entity abstracting information about a physical interface
associated with a host.

<table>
    <tr>
        <th>Field Name</th>
        <th>Type</th>
        <th>POST/PUT</th>
        <th>Required</th>
        <th>Description</th>
    </tr>
    <tr>
        <td>uri</td>
        <td>URI</td>
        <td/>
        <td/>
        <td>A GET against this URI refreshes the representation of this
         resource.</td>
    </tr>
    <tr>
        <td>id</td>
        <td>UUID</td>
        <td/>
        <td/>
        <td>A unique identifier of the resource. It is usually autogenerated by
         the daemon running on the host.</td>
    </tr>
    <tr>
        <td>hostId</td>
        <td>UUID</td>
        <td>PUT</td>
        <td/>
        <td>The unique identifier of the host that owns this interface</td>
    </tr>
    <tr>
        <td>name</td>
        <td>string</td>
        <td>PUT</td>
        <td/>
        <td>The interface physical name.</td>
    </tr>
    <tr>
        <td>mac</td>
        <td>string</td>
        <td>POST / PUT</td>
        <td/>
        <td>The interface physical address (mac).</td>
    </tr>
    <tr>
        <td>mtu</td>
        <td>integer</td>
        <td>POST / PUT</td>
        <td/>
        <td>The interface MTU value.</td>
    </tr>
    <tr>
        <td>status</td>
        <td>integer</td>
        <td>POST / PUT</td>
        <td/>
        <td>Bitmask of status flags. Currently we provide information about UP
         status and Carrier status (0x01, 0x02 respectively).</td>
    </tr>
    <tr>
        <td>type</td>
        <td>string</td>
        <td/>
        <td/>
        <td>Interface type (the best information that we have been able to
         infer). Can be:
Unknown | Physical | Virtual | Tunnel</td>
    </tr>
    <tr>
        <td>addresses</td>
        <td>multiArray of InetAddress</td>
        <td/>
        <td/>
        <td>The list of inet addresses bound to this interface.</td>
    </tr>
    <tr>
        <td>properties</td>
        <td>map&lt;string, string&gt;</td>
        <td>POST / PUT</td>
        <td/>
        <td>A set of key / value pairs containing other information about the
         interface: for example: ovs external id, etc.
<p>
The only known property that is updatable is the “midonet_port_id” property.
</p>
<p>
When read it will be set to the midonet_port_id that this interface is
associated with.
</p>
<p>
When updated it will cause this interface to be reassociated
with the new port_id.
</p>
<p>
When cleared (set to “”) it will cause the interface to be become not
associated with any midonet port.
</p>
        </td>
    </tr>
</table>

<a name="hostcommand"/>
### HostCommand [application/vnd.com.midokura.midolman.mgmt.HostCommand+json]

This is the description of the command generated by an Interface PUT
operation. For each host there is going to be a list of HostCommand objects
intended to be executed sequentially to make sure that the local host
configuration is kept uptodate.

<table>
    <tr>
        <th>Field Name</th>
        <th>Type</th>
        <th>POST/PUT</th>
        <th>Required</th>
        <th>Description</th>
    </tr>
    <tr>
        <td>uri</td>
        <td>URI</td>
        <td/>
        <td/>
        <td>A GET against this URI refreshes the representation of this
         resource.</td>
    </tr>
    <tr>
        <td>id</td>
        <td>UUID</td>
        <td/>
        <td/>
        <td>A unique identifier of the resource. It is usually autogenerated by
         the daemon running on the host.</td>
    </tr>
    <tr>
        <td>hostId</td>
        <td>UUID</td>
        <td/>
        <td/>
        <td>The unique identifier of the host that is the target of this
         command</td>
    </tr>
    <tr>
        <td>interfaceName</td>
        <td>string</td>
        <td/>
        <td/>
        <td>The name of the interface targeted by this command.</td>
    </tr>
    <tr>
        <td>commands</td>
        <td>array of Command</td>
        <td/>
        <td/>
        <td>Each Command has three properties: [operation, property, value].
         The operation can be one of: SET|DELETE|CLEAR.
The property can be one of: mtu, address, mac, interface, midonet_port_id.
The value is the value of the operation as a string.</td>
    </tr>
    <tr>
        <td>logEntries</td>
        <td>array of LogEntry</td>
        <td/>
        <td/>
        <td>A log entry contains a timestamp (which is a unix time long) and a
         string which is the error message that was generated at the moment.
         </td>
    </tr>
</table>

<a name="hostinterfaceport"/>
### Host-Interface-Port Binding [application/vnd.com.midokura.midolman.mgmt.HostInterfacePort+json]

The HostInterfacePort binding allows mapping a virtual network port to an
interface (virtual or physical) of a physical host where Midolman is running.
It contains the following fields:

<table>
    <tr>
        <th>Field Name</th>
        <th>Type</th>
        <th>POST/PUT</th>
        <th>Required</th>
        <th>Description</th>
    </tr>
    <tr>
        <td>uri</td>
        <td>URI</td>
        <td/>
        <td/>
        <td>A GET against this URI refreshes the representation of this
         resource.</td>
    </tr>
    <tr>
        <td>hostId</td>
        <td>UUID</td>
        <td>POST/PUT</td>
        <td>Yes</td>
        <td>A unique identifier of the host resource. It is usually
        autogenerated by the daemon running on the host.</td>
    </tr>
    <tr>
        <td>interfaceName</td>
        <td>String</td>
        <td>POST/PUT</td>
        <td>Yes</td>
        <td>The interface physical name.</td>
    </tr>
    <tr>
        <td>portId</td>
        <td>UUID</td>
        <td>POST/PUT</td>
        <td>Yes</td>
        <td>A unique identifier of the port resource.</td>
    </tr>
</table>

<a name="tunnelzone"/>
### TunnelZone [application/vnd.com.midokura.midolman.mgmt.TunnelZone+json]

Tunnel zone represents a group in which hosts can be included to form an
isolated zone for tunneling. It contains the following fields:

<table>
    <tr>
        <th>Field Name</th>
        <th>Type</th>
        <th>POST/PUT</th>
        <th>Required</th>
        <th>Description</th>
    </tr>
    <tr>
        <td>uri</td>
        <td>URI</td>
        <td/>
        <td/>
        <td>A GET against this URI refreshes the representation of this
         resource.</td>
    </tr>
    <tr>
        <td>id</td>
        <td>UUID</td>
        <td/>
        <td/>
        <td>A unique identifier of the resource.</td>
    </tr>
    <tr>
        <td>name</td>
        <td>String</td>
        <td>POST/PUT</td>
        <td>Yes</td>
        <td>The name of the resource.</td>
    </tr>
    <tr>
        <td>type</td>
        <td>String</td>
        <td>POST</td>
        <td>Yes</td>
        <td>Tunnel type.  Currently this value can only be ‘GRE’.</td>
    </tr>
</table>

<a name="tunnelzonehost"/>
### TunnelZoneHost [application/vnd.com.midokura.midolman.mgmt.TunnelZoneHost+json]

Represents a host's membership in a tunnel zone:

<table>
    <tr>
        <th>Field Name</th>
        <th>Type</th>
        <th>POST/PUT</th>
        <th>Required</th>
        <th>Description</th>
    </tr>
    <tr>
        <td>uri</td>
        <td>URI</td>
        <td/>
        <td/>
        <td>A GET against this URI refreshes the representation of this
         resource.</td>
    </tr>
    <tr>
        <td>id</td>
        <td>UUID</td>
        <td/>
        <td/>
        <td>A unique identifier of the resource.</td>
    </tr>
    <tr>
        <td>tunnelZoneId</td>
        <td>UUID</td>
        <td></td>
        <td></td>
        <td>ID of the tunnel zone that the host is a member of.</td>
    </tr>
    <tr>
        <td>tunnelZone</td>
        <td>URI</td>
        <td/>
        <td/>
        <td>A GET against this URI retrieves the tunnel zone.</td>
    </tr>
    <tr>
        <td>hostId</td>
        <td>UUID</td>
        <td>POST</td>
        <td>Yes</td>
        <td>ID of the host that you want to add to a tunnel zone.</td>
    </tr>
    <tr>
        <td>host</td>
        <td>URI</td>
        <td/>
        <td/>
        <td>A GET against this URI retrieves the host.</td>
    </tr>
    <tr>
        <td>ipAddress</td>
        <td>String</td>
        <td>POST/PUT</td>
        <td>Yes for GRE tunnel zone type</td>
        <td>IP address to use for the GRE tunnels from this host.</td>
    </tr>
</table>

<a name="metrictarget"/>
### Metric Target [application/vnd.com.midokura.midolman.mgmt.MetricTarget+json]

Represents an object that can be associated with a metric. POST a
MetricTarget (uri: /filter) to get an array of Metric associated with this
target.

<table>
    <tr>
        <th>Field Name</th>
        <th>Type</th>
        <th>POST/PUT</th>
        <th>Required</th>
        <th>Description</th>
    </tr>
    <tr>
        <td>targetIdentifier</td>
        <td>UUID</td>
        <td/>
        <td/>
        <td>The UUID of the object for which this metric was collected. For
         example host id or vif id)</td>
    </tr>
</table>

<a name="metric"/>
### Metric [application/vnd.com.midokura.midolman.mgmt.collection.Metric+json]

It’s an entity representing a metric in the monitoring system.

<table>
    <tr>
        <th>Field Name</th>
        <th>Type</th>
        <th>POST/PUT</th>
        <th>Required</th>
        <th>Description</th>
    </tr>
    <tr>
        <td>name</td>
        <td>String</td>
        <td/>
        <td/>
        <td>The name of the metric</td>
    </tr>
    <tr>
        <td>targetIdentifier</td>
        <td>UUID</td>
        <td/>
        <td/>
        <td>The object for which this metric was collected</td>
    </tr>
    <tr>
        <td>type </td>
        <td>String</td>
        <td/>
        <td/>
        <td>The category to which the metric belongs, (for example
         VMMetricsCollection that include thread counts, memory usage, etc.)
         </td>
    </tr>
</table>

<a name="metricquery"/>
### Metric Query [application/vnd.com.midokura.midolman.mgmt.MetricQuery+json]

It’s an entity that represent a query to the monitoring system. POST a
collection of MetricQuery (uri: /query) to get a collection of
MetricQueryResponse containing the result of the queries.

<table>
    <tr>
        <th>Field Name</th>
        <th>Type</th>
        <th>POST/PUT</th>
        <th>Required</th>
        <th>Description</th>
    </tr>
    <tr>
        <td>targetIdentifier</td>
        <td>UUID</td>
        <td/>
        <td/>
        <td>The object for which this metric was collected</td>
    </tr>
    <tr>
        <td>metricName</td>
        <td>String</td>
        <td/>
        <td/>
        <td>The name of the metric we want to query</td>
    </tr>
    <tr>
        <td>type</td>
        <td>String</td>
        <td/>
        <td/>
        <td>The category to which the metric belongs</td>
    </tr>
    <tr>
        <td>timeStampStart</td>
        <td>long</td>
        <td/>
        <td/>
        <td>The starting point of the interval we want to query</td>
    </tr>
    <tr>
        <td>timeStampEnd</td>
        <td>long</td>
        <td/>
        <td/>
        <td>The ending point of the interval</td>
    </tr>
</table>

<a name="metricqueryresponse"/>
### Metric Query Response [application/vnd.com.midokura.midolman.mgmt.MetricQueryResponse+json]

It represents the result of a query to the monitoring system.

<table>
    <tr>
        <th>Field Name</th>
        <th>Type</th>
        <th>POST/PUT</th>
        <th>Required</th>
        <th>Description</th>
    </tr>
   <tr>
        <td>targetIdentifier</td>
        <td>UUID</td>
        <td/>
        <td/>
        <td>The object for which this metric was collected</td>
    </tr>
    <tr>
        <td>metricName</td>
        <td>String</td>
        <td/>
        <td/>
        <td>The name of the metric we want to query</td>
    </tr>
    <tr>
        <td>type</td>
        <td>String</td>
        <td/>
        <td/>
        <td>The category to which the metric belongs</td>
    </tr>
    <tr>
        <td>timeStampStart</td>
        <td>long</td>
        <td/>
        <td/>
        <td>The starting point of the interval we want to query</td>
    </tr>
    <tr>
        <td>timeStampEnd</td>
        <td>long</td>
        <td/>
        <td/>
        <td>The ending point of the interval</td>
    </tr>
    <tr>
        <td>results</td>
        <td>Map&lt;String, long&gt;</td>
        <td/>
        <td/>
        <td>The list of the result, where the key is the timestamp and the
         value is the value of the metric at that time</td>
    </tr>
</table>

<a name="resourcecollection"/>
### Resource Collection [application/vnd.com.midokura.midolman.mgmt.collection.resource+json]

A collection of a resource is represented by the media type format:

*application/vnd.com.midokura.midolman.mgmt.collection.xxxx+json*

where xxxx is the resource name.  The media type of a collection of tenants,
for example, would be:

*vnd.com.midokura.midolman.mgmt.collection.Tenant+json*

<a name="acronyms"/>
## List of Acronyms

* API:  Application Programmable Interface
* BGP:  Border Gateway Protocol
* HTTP:  HyperText Transfer Protocol
* ICMP:  Internet Control Message Protocol
* JSON:  JavaScript Object Notation
* REST:  REpresentational State Transfer
* TOS:  Type Of Service
* URI:  Unique Resource Identified
* URL:  Uniform Resource Locator
* VIF:  Unique Resource Identified
