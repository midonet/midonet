# MidoNet API Specification (v1.1)

## Table of Contents

* [Introduction](#introduction)
* [Getting Started](#getstarted)
* [Common Behaviors](#commonbehaviors)
* [Media Types](#mediatypes)
* [Request Headers](#requestheaders)
* [Response Headers](#responseheaders)
* [HTTP Status Codes](#statuscodes)
* [URI Templates](#uritemplates)
* [Resource Models](#resourcemodels)
  * [Application](#application)
  * [Application - v1, Deprecated](#application-v1)
  * [Router](#router)
  * [Bridge](#bridge)
  * [Bridge MAC Table](#bridgemactable)
  * [Bridge MAC Table - v1, Deprecated](#bridgemactable-v1)
  * [Bridge ARP Table](#bridgearptable)
  * [Port](#port)
  * [Port - v1, Deprecated](#port-v1)
  * [Port Link](#portlink)
  * [Route](#route)
  * [Port Group](#portgroup)
  * [Port Group Port](#portgroupport)
  * [Chain](#chain)
  * [Rule](#rule)
  * [BGP](#bgp)
  * [Route Advertisement](#routeadvertisement)
  * [Host](#host)
  * [Interface](#interface)
  * [Host Command](#hostcommand)
  * [Host-Interface-Port Binding](#hostinterfaceport)
  * [Tenant](#tenant)
  * [Tunnel Zone](#tunnelzone)
  * [Tunnel Zone Host](#tunnelzonehost)
  * [Metric Target](#metrictarget)
  * [Metric](#metric)
  * [Metric Query](#metricquery)
  * [Metric Query Response](#metricqueryresponse)
  * [Trace Capture Conditions](#traceconditions)
  * [System State](#systemstate)
  * [Write Version](#writeversion)
  * [Token](#token)
  * [Host Version](#hostversion)
* [Resource Collection](#resourcecollection)
* [Authentication/Authorization](#auth)
* [List of Acronyms](#acronyms)

<a name="introduction"></a>
## Introduction

This document specifies a RESTful API for creating and managing MidoNet
resources.  The API uses JSON as its format.

<a name="getstarted"></a>
## Getting Started

This section is intended to help users get started on using the API.  It assumes
that the MidoNet Management REST API host is known.  This host is represented
as ‘example.org’ in this document.  The following GET request to the base URL of
the API reveals the locations of the available resources.

    GET /
    Host: example.org
    Accept: application/vnd.org.midonet.Application-v1+json

The request above may yield the following output:


    HTTP/1.1 200 OK
    Content-Type: application/vnd.org.midonet.Application-v1+json
    {
        "uri": "http://example.org/",
        "version": "1",
        "bridges": "http://example.org/bridges",
        "chains": "http://example.org/chains",
        "hosts": "http://example.org/hosts",
        "metricsFilter": "http://example.org/metrics/filter",
        "metricsQuery": "http://example.org/metrics/query",
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

<a name="commonbehaviors"></a>
## Common Behaviors

This section specifies the common constraints that apply to all the requests
and responses that occur in the MidoNet Management REST API.

<a name="mediatypes"></a>
### Media Types

In MidoNet REST API, the resources are encoded in JSON, as specified in
RFC 4267.  Each type of resource has its own media-type, which matches the
pattern:

*application/vnd.org.midonet.xxxxx-v1+json*

where “xxxxx“ represents the unique resource identifier.

<a name="requestheaders"></a>
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

<a name="responseheaders"></a>
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

<a name="statuscodes"></a>
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

<a name="uritemplates"></a>
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

<a name="resourcemodels"></a>
## Resource Models

This section specifies the representations of the MidoNet REST API resources.
Each type of resource has its own Internet Media Type. The media type for each
resource is included in square brackets in the corresponding section header.

The POST/PUT column indicates whether the field can be included in the request
with these verbs.  If they are not specified, the field should not be included
in the request.

The Required column indicates is only relevant for POST/PUT operations.
You should not see any entry for 'Required' if the 'POST/PUT' column is
empty. When the Required value is set, it will have indicate whether the
field is relevant for POST, PUT or both. Required fields need to be
included in the request to create/update the object. Note that fields
may be required for PUT but not POST, and viceversa.  In this case it
will be indicated in the specific cell for the field.

<a name ="application"></a>
### Application [application/vnd.org.midonet.Application-v2+json]

    GET     /

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
        <td>tenants</td>
        <td>URI</td>
        <td></td>
        <td></td>
        <td>A GET against this URI gets a list of tenants.</td>
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
    <tr>
        <td>metricsFilter</td>
        <td>URI</td>
        <td></td>
        <td></td>
        <td>A POST against this URI gets a list of metrics
        available for a given metric target.</td>
    </tr>
        <td>metricsQuery</td>
        <td>URI</td>
        <td></td>
        <td></td>
        <td>A POST against this URI gets a list of metric
        query responses for a given list of metric queries.</td>
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
        <td>tenantTemplate</td>
        <td>String</td>
        <td></td>
        <td></td>
        <td>Template of the URI that represents the location of tenant with the
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

<a name="application-v1"></a>
### Application - v1, Deprecated [application/vnd.org.midonet.Application-v1+json]

    GET     /

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
    <tr>
        <td>metricsFilter</td>
        <td>URI</td>
        <td></td>
        <td></td>
        <td>A POST against this URI gets a list of metrics
        available for a given metric target.</td>
    </tr>
        <td>metricsQuery</td>
        <td>URI</td>
        <td></td>
        <td></td>
        <td>A POST against this URI gets a list of metric
        query responses for a given list of metric queries.</td>
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
    <tr>
        <td>traces</td>
        <td>URI</td>
        <td></td>
        <td></td>
        <td>A GET against this URI returns a list of traces</td>
    </tr>
    <tr>
        <td>traceConditions</td>
        <td>URI</td>
        <td></td>
        <td></td>
        <td>A GET against this URI returns a list of trace conditions</td>
    </tr>
    <tr>
        <td>traceTemplate</td>
        <td>String</td>
        <td></td>
        <td></td>
        <td>Template of the URI that represents the location of the trace
        with the provided ID.</td>
    </tr>
    <tr>
        <td>traceConditionTemplate</td>
        <td>URI</td>
        <td></td>
        <td></td>
        <td>Template of the URI that represents the location of the trace
        condition with the provided ID.</td>
    </tr>
    <tr>
        <td>writeVersion</td>
        <td>URI</td>
        <td></td>
        <td></td>
        <td>URI that represents the location of the write version
        information</td>
    </tr>
    <tr>
        <td>systemState</td>
        <td>URI</td>
        <td></td>
        <td></td>
        <td>URI that represents the location of the system state
        information</td>
    </tr>
</table>

<a name="router"></a>
### Router [application/vnd.org.midonet.Router-v1+json]

    GET     /routers
    GET     /tenants/:tenantId/routers
    GET     /routers/:routerId
    POST    /routers
    PUT     /routers/:routerId
    DELETE  /routers/:routerId

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
        <td>A GET against this URI retrieves the interior ports attached to this
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

#### <u>Query Parameters</u>

<table>
    <tr>
        <th>Name</th>
        <th>Description</th>
    </tr>
    <tr>
        <td>tenant_id</td>
        <td>
            ID of the tenant to filter the search with.
        </td>
    </tr>
</table>


<a name="bridge"></a>
### Bridge [application/vnd.org.midonet.Bridge-v1+json]

    GET     /bridges
    GET     /tenants/:tenantId/bridges
    GET     /bridges/:bridgeId
    POST    /bridges
    PUT     /bridges/:bridgeId
    DELETE  /bridges/:bridgeId

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
        <td>macTable</td>
        <td>URI</td>
        <td/>
        <td/>
        <td>A GET against this URI retrieves the bridge's MAC table.</td>
    </tr>
    <tr>
        <td>peerPorts</td>
        <td>URI</td>
        <td/>
        <td/>
        <td>A GET against this URI retrieves the interior ports attached to this
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

#### <u>Query Parameters</u>

<table>
    <tr>
        <th>Name</th>
        <th>Description</th>
    </tr>
    <tr>
        <td>tenant_id</td>
        <td>
            ID of the tenant to filter the search with.
        </td>
    </tr>
</table>

<a name="bridgemactable"></a>
### MacPort [application/vnd.org.midonet.midolman.mgmt.MacPort-v2+json]

    GET     /bridges/:bridgeId/mac_table
    GET     /bridges/:bridgeId/vlans/:vlanId/mac_table
    GET     /bridges/:bridgeId/mac_table/:macPortPair
    GET     /bridges/:bridgeId/vlans/:vlanId/mac_table/:macPortPair
    POST    /bridges/:bridgeId/mac_table
    POST    /bridges/:bridgeId/vlans/:vlanId/mac_table
    DELETE  /bridges/:bridgeId/mac_table/:macPortPair
    DELETE  /bridges/:bridgeId/vlans/:vlanId/mac_table/:macPortPair

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
        <td>vlanId</td>
        <td>short</td>
        <td/>
        <td/>
        <td>ID of the VLAN to which the port with ID portId belongs. This
        field is used only in responses to GET requests and will be ignored
        in POST requests.</td>
    </tr>
    <tr>
        <td>macAddr</td>
        <td>String</td>
        <td/>
        <td>Yes</td>
        <td>A MAC address in the form "aa:bb:cc:dd:ee:ff"</td>
    </tr>
    <tr>
        <td>portId</td>
        <td>UUID</td>
        <td/>
        <td>Yes</td>
        <td>ID of the port to which the packets destined to the macAddr will
        be emitted.</td>
    </tr>
</table>

#### <u>Path Parameters</u>

<table>
    <tr>
        <th>Name</th>
        <th>Description</th>
    </tr>
    <tr>
        <td>bridgeId</td>
        <td>
            UUID of the bridge owning the MAC table to query or modify.
        </td>
    </tr>
    <tr>
        <td>vlanId</td>
        <td>
            ID of the VLAN owning the MAC table to query or modify.
        </td>
    </tr>
    <tr>
        <td>macPortPair</td>
        <td>
            Consists of a MAC address in the form "12-34-56-78-9a-bc"
            and the destination port's ID, separated by an underscore.
            For example:
            "12-34-56-78-9a-bc_01234567-89ab-cdef-0123-4567890abcdef".
        </td>
    </tr>
</table>

<a name="bridgemactable-v1"></a>
### MacPort - v1, Deprecated [application/vnd.org.midonet.midolman.mgmt.MacPort-v1+json]

    GET     /bridges/:bridgeId/mac_table
    GET     /bridges/:bridgeId/mac_table/:macPortPair
    POST    /bridges/:bridgeId/mac_table
    DELETE  /bridges/:bridgeId/mac_table/:macPortPair

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
        <td>macAddr</td>
        <td>String</td>
        <td/>
        <td>Yes</td>
        <td>A MAC address in the form "aa:bb:cc:dd:ee:ff"</td>
    </tr>
    <tr>
        <td>portId</td>
        <td>UUID</td>
        <td/>
        <td>Yes</td>
        <td>ID of the port to which the packets destined to the macAddr will
        be emitted.</td>
    </tr>
</table>

#### <u>Path Parameters</u>

<table>
    <tr>
        <th>Name</th>
        <th>Description</th>
    </tr>
    <tr>
        <td>bridgeId</td>
        <td>
            UUID of the bridge owning the MAC table to query or modify.
        </td>
    </tr>
    <tr>
        <td>vlanId</td>
        <td>
            ID of the VLAN owning the MAC table to query or modify.
        </td>
    </tr>
    <tr>
        <td>macPortPair</td>
        <td>
            Consists of a MAC address in the form "12-34-56-78-9a-bc"
            and the destination port's ID, separated by an underscore.
            For example:
            "12-34-56-78-9a-bc_01234567-89ab-cdef-0123-4567890abcdef".
        </td>
    </tr>
</table>

<a name="bridgearptable"></a>
### IP4MacPair [application/vnd.org.midonet.IP4arp+json]

    GET     /bridges/:bridgeId/arp_table
    GET     /bridges/:bridgeId/arp_table/:ip4MacPair
    POST    /bridges/:bridgeId/arp_table
    DELETE  /bridges/:bridgeId/arp_table/:ip4MacPair

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
        <td>ip</td>
        <td>String</td>
        <td/>
        <td>Yes</td>
        <td>IP version 4 address in the form "1.2.3.4".</td>
    </tr>
    <tr>
        <td>mac</td>
        <td>String</td>
        <td/>
        <td>Yes</td>
        <td>A MAC address in the form "aa:bb:cc:dd:ee:ff". If ARP replies
        are enabled on the bridge, the ip will resolve to this MAC.</td>
    </tr>
</table>

<a name="dhcp"></a>
### DHCP Subnet [application/vnd.org.midonet.DhcpSubnet+json]

    GET     /bridges/:bridgeId/dhcp
    GET     /bridges/:bridgeId/dhcp/:subnetAddr
    POST    /bridges/:bridgeId/dhcp
    DELETE  /bridges/:bridgeId/dhcp/:subnetAddr

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
        <td>A GET against this URI returns or refreshes the representation
         of this resource.</td>
    </tr>
    <tr>
        <td>subnetPrefix</td>
        <td>String</td>
        <td>POST</td>
        <td>No</td>
        <td>Subnet Prefix in the form of "1.2.3.4"</td>
    </tr>
    <tr>
        <td>subnetLength</td>
        <td>Integer</td>
        <td>POST</td>
        <td>No</td>
        <td>Subnet Length (0-32)</td>
    </tr>
    <tr>
        <td>defaultGateway</td>
        <td>String</td>
        <td>POST</td>
        <td>No</td>
        <td>Default Gateway in the form "1.2.3.4"</td>
    </tr>
    <tr>
        <td>serverAddr</td>
        <td>String</td>
        <td>POST</td>
        <td>No</td>
        <td>DHCP Server Address in the form of "1.2.3.4"</td>
    </tr>
    <tr>
        <td>dnsServerAddrs</td>
        <td>List(String)</td>
        <td>POST</td>
        <td>No</td>
        <td>List of DNS Server Addresses in the form of "1.2.3.4"</td>
    </tr>
    <tr>
        <td>interfaceMTU</td>
        <td>Integer</td>
        <td>POST</td>
        <td>No</td>
        <td>Interface Maximum Transmission Unit advertised by DHCP</td>
    </tr>
    <tr>
        <td>opt121Routes</td>
        <td>List(String, Integer, String)</td>
        <td>POST</td>
        <td>No</td>
        <td>List of DHCP Option 121 routes, each of which consists of
         {destination prefix (String, "1.2.3.4" form),
          destination prefix length (Integer, 0-32),
          gateway address (String, "1.2.3.4" form)}
        </td>
    </tr>
</table>

<a name="port"></a>
### Port [application/vnd.org.midonet.Port-v2+json]

    GET     /ports/:portId
    GET     /routers/:routerId/ports
    GET     /routers/:routerId/peer_ports
    GET     /bridges/:bridgeId/ports
    GET     /bridges/:bridgeId/peer_ports
    POST    /routers/:routerId/ports
    POST    /bridges/:bridgeId/ports
    PUT     /ports/:portId
    DELETE  /ports/:portId

Port is an entity that represents a port on a virtual device (bridge or
router) in MidoNet.  It contains the following fields:

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
        <td>Type of device port.  It must be one of:
<ul>
<li>Router</li>
<li>Bridge</li>
</ul>
<p>
A new router or bridge port is unplugged. Depending on what it
is later attached to, it is referred to as an exterior or interior port.
</p>
<p>
An exterior router port is a virtual port that plugs into the VIF of an
entity, such as a VM. It can also be a virtual port connected to a host
physical port, directly or after implementing tunnel encapsulation.
Access to exterior ports is managed by OpenVSwitch (OpenFlow switch).
Exterior bridge port is the same as exterior router port but it is a
port on a virtual bridge.
Upon being bound to an interface, the port becomes exterior and will have the
hostId, host, and interfaceName fields be non-null. The peer and peerId
fields will be null.
</p>
<p>
An interior router port is a virtual port that only exists in the MidoNet
virtual router network abstraction. It refers to a logical connection to
another virtual networking device such as another router. An interior
bridge port is the equivalent on a virtual bridge.
Upon being linked to a peer, a port will become interior and will have the
peer and peerId fields be non-null. The hostId, host, and interfaceName fields
will be null.
</p>
</span></td>
    </tr>
    <tr>
        <td>peerId</td>
        <td>UUID</td>
        <td/>
        <td/>
        <td>ID of the peer port that this port is linked to. This will be
        set when linking a port to another peer (becoming an interior port)
        </td>
    </tr>
    <tr>
        <td>peer</td>
        <td>URI</td>
        <td/>
        <td/>
        <td>A GET against this URI retrieves the peer port resource.
        Requires a port to be linked to another port.</td>
    </tr>
    <tr>
        <td>networkAddress (Router only)</td>
        <td>String</td>
        <td>POST</td>
        <td>Yes</td>
        <td>IP address of the network attached to this port. For example
         192.168.10.32</td>
    </tr>
    <tr>
        <td>networkLength (Router only)</td>
        <td>Int</td>
        <td>POST</td>
        <td>Yes</td>
        <td>Prefix length of the network attached to this port (number of
        fixed network bits).</td>
    </tr>
    <tr>
        <td>portAddress (Router only)</td>
        <td>String</td>
        <td>POST</td>
        <td>Yes</td>
        <td>IP address assigned to the port.</td>
    </tr>
    <tr>
        <td>portMac (Router only)</td>
        <td>String</td>
        <td>POST</td>
        <td></td>
        <td>Port mac address.</td>
    </tr>
    <tr>
        <td>vifId</td>
        <td>UUID</td>
        <td/>
        <td/>
        <td>ID of the VIF plugged into the port.</td>
    </tr>
    <tr>
        <td>hostId</td>
        <td>UUID</td>
        <td/>
        <td>No</td>
        <td>ID of the port's host. This will be set when binding a port to a
            host (becoming an exterior port ). </td>
    </tr>
    <tr>
        <td>host</td>
        <td>URI</td>
        <td/>
        <td/>
        <td>The port host's URI. Requires a port to be bound to a host.</td>
    </tr>

    <tr>
        <td>interfaceName</td>
        <td>String</td>
        <td/>
        <td/>
        <td>Interface name of a bound port. This will be set when binding
            a port to a host (becoming an exterior port). </td>
    </tr>
    <tr>
        <td>bgps (Router only)</td>
        <td>URI</td>
        <td/>
        <td/>
        <td>A GET against this URI retrieves BGP configurations for this
            port.</td>
    </tr>
    <tr>
        <td>link</td>
        <td>URI</td>
        <td/>
        <td/>
        <td>Location of the port link resource.
         A POST against this URI links two interior ports.  In the body of the
         request, 'peerId' must be specified to indicate the peer interior port
         ID.  A DELETE against this URI removes the link.</td>
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
    <tr>
        <td>portGroups</td>
        <td>URI</td>
        <td/>
        <td/>
        <td>A GET against this URI retrieves the port groups that this port
        is a member of.</td>
    </tr>
    <tr>
        <td>hostInterfacePort</td>
        <td>URI</td>
        <td/>
        <td/>
        <td>A GET against this URI retrieves the interface-binding information
        of this port.</td>
    </tr>
    <tr>
        <td>vlanId (Bridge only)</td>
        <td>Short</td>
        <td>POST</td>
        <td>No</td>
        <td>The VLAN ID assigned to this port. On a given bridge, each VLAN ID
            can be present at most in one interior port.</td>
    </tr>
</table>

<a name="port-v1"></a>
### Port-v1 (deprecated) [application/vnd.org.midonet.Port-v1+json]

    GET     /ports/:portId
    GET     /routers/:routerId/ports
    GET     /routers/:routerId/peer_ports
    GET     /bridges/:bridgeId/ports
    GET     /bridges/:bridgeId/peer_ports
    POST    /routers/:routerId/ports
    POST    /bridges/:bridgeId/ports
    PUT     /ports/:portId
    DELETE  /ports/:portId

This port type has been deprecated. Please use the updated v2 Port api
described above.
Port is an entity that represents a port on a virtual device (bridge or
router) in MidoNet.  It contains the following fields:

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
        <td>Type of device port.  It must be one of:
<ul>
<li>ExteriorRouter</li>
<li>InteriorRouter</li>
<li>ExteriorBridge</li>
<li>InteriorBridge</li>
</ul>
<p>
Exterior router port is a virtual port that plugs into the VIF of an
entity, such as a VM. It can also be a virtual port connected to a host
physical port, directly or after implementing tunnel encapsulation.
Access to exterior ports is managed by OpenVSwitch (OpenFlow switch).
Exterior bridge port is the same as exterior router port but it is a
port on a virtual bridge.
</p>
<p>
Interior router port is a virtual port that only exists in the MidoNet
virtual router network abstraction. It refers to a logical connection to
another virtual networking device such as another router.  Interior
bridge is the equivalent port type on a virtual bridge.
</p>
</span></td>
    </tr>
    <tr>
        <td>peerId (Interior)</td>
        <td>UUID</td>
        <td/>
        <td/>
        <td>ID of the peer port that this port is linked to.</td>
    </tr>
    <tr>
        <td>peer (Interior)</td>
        <td>URI</td>
        <td/>
        <td/>
        <td>A GET against this URI retrieves the peer port resource.</td>
    </tr>
    <tr>
        <td>networkAddress (Router only)</td>
        <td>String</td>
        <td>POST</td>
        <td>Yes</td>
        <td>IP address of the network attached to this port. For example
         192.168.10.32/27</td>
    </tr>
    <tr>
        <td>networkLength (Router only)</td>
        <td>Int</td>
        <td>POST</td>
        <td>Yes</td>
        <td>Prefix length of the network attached to this port (number of
        fixed network bits).</td>
    </tr>
    <tr>
        <td>portAddress (Router only)</td>
        <td>String</td>
        <td>POST</td>
        <td>Yes</td>
        <td>IP address assigned to the port.</td>
    </tr>
    <tr>
        <td>vifId (Exterior and Trunk only)</td>
        <td>UUID</td>
        <td/>
        <td/>
        <td>ID of the VIF plugged into the port.</td>
    </tr>
    <tr>
        <td>bgps (Exterior router only)</td>
        <td>URI</td>
        <td/>
        <td/>
        <td>A GET against this BGP configurations for this port.</td>
    </tr>
    <tr>
        <td>link (Interior only)</td>
        <td>URI</td>
        <td/>
        <td/>
        <td>Location of the port link resource.
         A POST against this URI links two interior ports.  In the body of the
         request, 'peerId' must be specified to indicate the peer interior port
         ID.  A DELETE against this URI removes the link.</td>
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
    <tr>
        <td>portGroups</td>
        <td>URI</td>
        <td/>
        <td/>
        <td>A GET against this URI retrieves the port groups that this port
        is a member of.</td>
    </tr>
    <tr>
        <td>hostInterfacePort</td>
        <td>URI</td>
        <td/>
        <td/>
        <td>A GET against this URI retrieves the interface-binding information
        of this port.</td>
    </tr>
    <tr>
        <td>vlanId (Interior Bridge only)</td>
        <td>Short</td>
        <td>POST</td>
        <td>No</td>
        <td>The VLAN ID assigned to this port. On a given bridge, each VLAN ID
            can be present at most in one interior port.</td>
    </tr>
</table>

<a name="portlink"></a>
### Port Link [application/vnd.org.midonet.PortLink-v1+json]

    POST     /ports/:portId/link
    DELETE   /ports/:portId/link

Represents a link between two interior ports. Links are possible
between:

- Two router ports.
- A router port and a bridge port
- A router port and a bridge
- A bridge port and a bridge port
- Two Bridges, as long as just one of the two peers has a VLAN ID
  assigned. The Bridge owning this port will act as a VLAN-Aware Bridge,
  PUSH'ing and POP'ing VLAN IDs as frames traverse this port.

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
        <td>portId</td>
        <td>UUID</td>
        <td/>
        <td/>
        <td>A unique identifier of the port</td>
    </tr>
    <tr>
        <td>port</td>
        <td>URI</td>
        <td/>
        <td/>
        <td>A GET against this URI retrieves the port.</td>
    </tr>
    <tr>
        <td>peerId</td>
        <td>UUID</td>
        <td>POST</td>
        <td>yes</td>
        <td>A unique identifier of the peer port</td>
    </tr>
    <tr>
        <td>peer</td>
        <td>URI</td>
        <td/>
        <td/>
        <td>A GET against this URI retrieves the peer port.</td>
    </tr>
</table>

<a name="route"></a>
### Route [application/vnd.org.midonet.Route-v1+json]

    GET     /routes/:routeId
    GET     /routers/:routerId/routes
    POST    /routers/:routerId/routes
    PUT     /routers/:routerId/routes/:routeId
    DELETE  /routers/:routerId/routes/:routeId

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
        <td>The priority weight of the route. Lower weights take precedence over
            higher weights.</td>
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

<a name="portgroup"></a>
### Port Group [application/vnd.org.midonet.PortGroup-v1+json]

    GET     /port_groups
    GET     /tenants/:tenantId/port_groups
    GET     /ports/:portId/port_groups
    GET     /port_groups/:portGroupId
    POST    /port_groups
    PUT     /port_groups/:portGroupId
    DELETE  /port_groups/:portGroupId

Port group is a group of ports. Port groups are owned by tenants. A port could
belong to multiple port groups as long as they belong to the same tenant. A
port group can be specified in the chain rule to filter the traffic coming from
all the ports belonging to that the specified group.

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
    <tr>
        <td>ports</td>
        <td>URI</td>
        <td/>
        <td/>
        <td>URI for port membership operations.</td>
</table>

#### <u>Query Parameters</u>

<table>
    <tr>
        <th>Name</th>
        <th>Description</th>
    </tr>
    <tr>
        <td>tenant_id</td>
        <td>
            ID of the tenant to filter the search with.
        </td>
    </tr>
    <tr>
        <td>port_id</td>
        <td>
            ID of the port to filter the search with.
        </td>
    </tr>
</table>

<a name="portgroupport"></a>
### Port Group Port [application/vnd.org.midonet.PortGroupPort-v1+json]

    GET     /port_groups/:portGroupId/ports
    GET     /port_groups/:portGroupId/ports/:portId
    POST    /port_groups/:portGroupId/ports
    DELETE  /port_groups/:portGroupId/ports/:portId

PortGroupPort represents membership of ports in port groups.

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
        <td>portGroupId</td>
        <td>UUID</td>
        <td/>
        <td/>
        <td>ID of the port group that a port is a member of.</td>
    </tr>
    <tr>
        <td>portGroup</td>
        <td>URI</td>
        <td/>
        <td/>
        <td>URI to fetch the port group.</td>
     </tr>
    <tr>
        <td>portId</td>
        <td>UUID</td>
        <td>POST</td>
        <td>Yes</td>
        <td>ID of the port in a port group membership.</td>
     </tr>
    <tr>
        <td>port</td>
        <td>URI</td>
        <td/>
        <td/>
        <td>URI to fetch the port.</td>
     </tr>
</table>

<a name="chain"></a>
### Chain [application/vnd.org.midonet.Chain-v1+json]

    GET     /chains
    GET     /tenants/:tenantId/chains
    GET     /chains/:chainId
    POST    /chains
    DELETE  /chains/:chainId

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

#### <u>Query Parameters</u>

<table>
    <tr>
        <th>Name</th>
        <th>Description</th>
    </tr>
    <tr>
        <td>tenant_id</td>
        <td>
            ID of the tenant to filter the search with.
        </td>
    </tr>
</table>

<a name="rule"></a>
### Rule [application/vnd.org.midonet.Rule-v1+json]

    GET     /chains/:chainId/rules
    GET     /rules/:ruleId
    POST    /chains/:chainId/rules
    DELETE  /rules/:ruleId

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
        <td>dlDst</td>
        <td>String</td>
        <td>POST</td>
        <td>No</td>
        <td>The data link layer destination that this rule matches on. A MAC
         address in the form "aa:bb:cc:dd:ee:ff"</td>
    </tr>
    <tr>
        <td>dlSrc</td>
        <td>String</td>
        <td>POST</td>
        <td>No</td>
        <td>The data link layer source that this rule matches on. A MAC
         address in the form "aa:bb:cc:dd:ee:ff"</td>
    </tr>
    <tr>
        <td>dlType</td>
        <td>Short</td>
        <td>POST</td>
        <td>No</td>
        <td>Set the data link layer type (ethertype) of packets matched by
         this rule. The type provided is not check for validity.</td>
    </tr>
    <tr>
        <td>flowAction</td>
        <td>String</td>
        <td>POST</td>
        <td>No</td>
        <td>Action to take on each flow. If the type is snat, dnat, rev_snat and
         rev_dnat then this field is required. Must be one of accept, continue,
         return.</td>
    </tr>
    <tr>
        <td>id</td>
        <td>UUID</td>
        <td/>
        <td/>
        <td>A unique identifier of the resource.</td>
    </tr>
    <tr>
        <td>inPorts</td>
        <td>UUID</td>
        <td>POST</td>
        <td>No</td>
        <td>The list of (interior or exterior) ingress port UUIDs to
         match.</td>
    </tr>
    <tr>
        <td>invDlDst</td>
        <td>Bool</td>
        <td>POST</td>
        <td>No</td>
        <td>Set whether the match on the data link layer destination should
         be inverted (match packets whose data link layer destination is NOT
         equal to dlDst). Will be stored, but ignored until dlDst is
         set.</td>
    </tr>
    <tr>
        <td>invDlSrc</td>
        <td>Bool</td>
        <td>POST</td>
        <td>No</td>
        <td>Set whether the match on the data link layer source should be
         inverted (match packets whose data layer link source is NOT equal to
         dlSrc). Will be stored, but ignored until dlSrc is set.</td>
    </tr>
    <tr>
        <td>invDlType</td>
        <td>Bool</td>
        <td>POST</td>
        <td>No</td>
        <td>Set whether the match on the data link layer type should be
         inverted (match packets whose data link layer type is NOT equal to
         the Ethertype set by dlType. Will be stored, but ignored until
         dlType is set.</td>
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
        <td>invNwDst</td>
        <td>Bool</td>
        <td>POST</td>
        <td>No</td>
        <td>Invert the IP dest prefix predicate. Match packets whose
         destination is NOT in the prefix.</td>
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
        <td>invNwSrc</td>
        <td>Bool</td>
        <td>POST</td>
        <td>No</td>
        <td>Invert the IP source prefix predicate. Match packets whose source
         is NOT in the prefix.</td>
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
        <td>invOutPorts</td>
        <td>Bool</td>
        <td>POST</td>
        <td>No</td>
        <td>Inverts the out_ports predicate. Match if the packet’s egress is
        NOT in out_ports.</td>
    </tr>
    <tr>
        <td>invTpDst</td>
        <td>Bool</td>
        <td>POST</td>
        <td>No</td>
        <td>Invert the destination TCP/UDP port range predicate. Match packets
         whose dest port is NOT in the range.</td>
    </tr>
    <tr>
        <td>invTpSrc</td>
        <td>Bool</td>
        <td>POST</td>
        <td>No</td>
        <td>Invert the source TCP/UDP port range predicate. Match packets whose
         source port is NOT in the range.</td>
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
        <td>natTargets</td>
        <td>Array of JSON objects</td>
        <td>POST</td>
        <td>No</td>
        <td>The list of nat targets. Each nat target should be an JSON object
            that contains the following fields:
<ul>
<li>addressFrom: The first IP address in the range of IP addresses used as NAT
targets. </li>
<li>addressTo: The last IP address in the range of IP addresses used as NAT
targets.</li>
<li>portFrom: The first port number in the range of port numbers used as NAT
targets.</li>
<li>portTo: The last port number in the range of port numbers used as NAT
targets.</li>
</ul>
         For an example: {"addressFrom": "1.2.3.4", "addressTo": "5.6.7.8",
         "portFrom": "22", "portTo": "80"}.  This field is required if the
         type is dnat or snat.</td>
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
        <td>nwProto</td>
        <td>Int</td>
        <td>POST</td>
        <td>No</td>
        <td>The Network protocol number to match (0-255).</td>
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
        <td>nwTos</td>
        <td>Int</td>
        <td>POST</td>
        <td>No</td>
        <td>The value of the IP packet TOS field to match (0-255).</td>
    </tr>
    <tr>
        <td>outPorts</td>
        <td>Array of UUID</td>
        <td>POST</td>
        <td>No</td>
        <td>The list of (interior or exterior) egress port UUIDs to match.
        </td>
    </tr>
    <tr>
        <td>portGroup</td>
        <td>UUID</td>
        <td>POST</td>
        <td>No</td>
        <td>ID of the port group that you want to filter traffic from.
            If matched, the filter action is applied to any packet coming
            from ports belonging to the specified port group.</td>
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
    <tr>
        <td>tpSrc</td>
        <td>Range</td>
        <td>POST</td>
        <td>No</td>
        <td>A JSON representation of the Range object representing the
        tcp/udp source port range to match, like {"start":80,"end":400}.
        When creating an ICMP rule, this field should be set to the ICMP
        type value. The absence of a Range will be interpreted as
        "any".</td>
    </tr>
    <tr>
        <td>tpDst</td>
        <td>Range</td>
        <td>POST</td>
        <td>No</td>
        <td>A JSON representation of the Range object representing the
        tcp/udp source port range to match, like {"start":80,"end":400}.
        When creating an ICMP rule, this field should be set to the ICMP
        code value. A null value in this field will be intepreted as
        "any".</td>
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
        <td>uri</td>
        <td>URI</td>
        <td/>
        <td/>
        <td>A GET against this URI refreshes the representation of this
         resource.</td>
    </tr>
</table>

<a name="bgp"></a>
### BGP [application/vnd.org.midonet.Bgp-v1+json]

    GET     /ports/:portId/bgps
    GET     /bgps/:bgpId
    POST    /ports/:portId/bgps
    DELETE  /bgps/:bgpId

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

<a name="routeadvertisement"></a>
### Route Advertisement [application/vnd.org.midonet.AdRoute-v1+json]

    GET     /bgps/:bgpId/ad_routes
    GET     /ad_routes/:adRouteId
    POST    /bgps/:bgpId/ad_routes
    DELETE  /ad_routes/:adRouteId

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
        <td>ID of the BGP configuration that this route advertisement is
            configured for.</td>
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

<a name="host"></a>
### Host [application/vnd.org.midonet.Host-v1+json]

    GET     /hosts
    GET     /hosts/:hostId
    PUT     /hosts/:hostId
    DELETE  /hosts/:hostId

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
        <td>Return true if the node-agent running on the host is connected to
            ZK.</td>
    </tr>
    <tr>
        <td>addresses</td>
        <td>MultiArray</td>
        <td/>
        <td/>
        <td>The of last seen ip addresses visible on the host</td>
    </tr>
    <tr>
        <td>interfaces</td>
        <td>URI</td>
        <td/>
        <td/>
        <td>A GET against this URI gets the interface names on this host.</td>
    </tr>
    <tr>
        <td>ports</td>
        <td>URI</td>
        <td/>
        <td/>
        <td>A GET against this URI gets the virtual ports bound to the
        interfaces on this host.</td>
    </tr>
</table>

<a name="interface"></a>
### Interface [application/vnd.org.midonet.Interface-v1+json]

    GET     /hosts/:hostId/interfaces
    GET     /hosts/:hostId/interfaces/:interfaceName
    POST    /hosts/:hostId/interfaces
    PUT     /hosts/:hostId/interfaces/:interfaceName

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
</table>

<a name="hostcommand"></a>
### Host Command [application/vnd.org.midonet.HostCommand-v1+json]

    GET     /hosts/:hostId/commands
    GET     /hosts/:hostId/commands/:hostCommandId
    DELETE  /hosts/:hostId/commands/:hostCommandId

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

<a name="hostinterfaceport"></a>
### HostInterfacePort [application/vnd.org.midonet.HostInterfacePort-v1+json]

    GET     /hosts/:hostId/ports
    GET     /hosts/:hostId/ports/:portId
    POST    /hosts/:hostId/ports
    DELETE  /hosts/:hostId/ports/:portId

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

<a name="tunnelzone"></a>
### Tunnel Zone [application/vnd.org.midonet.TunnelZone-v1+json]

    GET     /tunnel_zones
    GET     /tunnel_zones/:tunnelZoneId
    POST    /tunnel_zones
    PUT     /tunnel_zones/:tunnelZoneId
    DELETE  /tunnel_zones/:tunnelZoneId

Tunnel zone represents a group in which hosts can be included to form an
isolated zone for tunneling. They must have unique, case insensitive
names per type. It contains the following fields:

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

<a name="tunnelzonehost"></a>
### Tunnel Zone Host [application/vnd.org.midonet.TunnelZoneHost-v1+json]

    GET     /tunnel_zones/:tunnelZoneId/hosts
    GET     /tunnel_zones/:tunnelZoneId/hosts/:hostId
    POST    /tunnel_zones/:tunnelZoneId/hosts
    PUT     /tunnel_zones/:tunnelZoneId/hosts/:hostId
    DELETE  /tunnel_zones/:tunnelZoneId/hosts/:hostId

Especially the following two `GET` requests are allowed to specify the media
types to filter the responses.

    GET     /tunnel_zones/:tunnelZoneId/hosts
    GET     /tunnel_zones/:tunnelZoneId/hosts/:hostId

The media types below are available for each URI:

* `"application/vnd.org.midonet.collection.CapwapTunnelZoneHost-v1+json"`
* `"application/vnd.org.midonet.collection.GreTunnelZoneHost-v1+json"`
* `"application/vnd.org.midonet.collection.IpsecTunnelZoneHost-v1+json"`

and

* `"application/vnd.org.midonet.CapwapTunnelZoneHost-v1+json"`
* `"application/vnd.org.midonet.GreTunnelZoneHost-v1+json"`
* `"application/vnd.org.midonet.IpsecTunnelZoneHost-v1+json"`

Hosts in the same tunnel zone share the same tunnel configurations, and
they are allowed to create tunnels among themselves.

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

<a name="metrictarget"></a>
### Metric Target [application/vnd.org.midonet.MetricTarget-v1+json]

    POST    /metrics/filter

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

<a name="metric"></a>
### Metric [application/vnd.org.midonet.collection.Metric-v1+json]

    POST    /metrics/filter

It’s an entity representing a metric in the monitoring system.

List of metrics available:

##### VMMetricsCollection (for every MidoNet agent)
* AvailableProcessors
* CommittedHeapMemory
* FreePhysicalMemorySize
* FreeSwapSpaceSize
* MaxHeapMemory
* OpenFileDescriptorCount
* ProcessCPUTime
* SystemLoadAverage
* ThreadCount
* TotalPhysicalMemorySize
* TotalSwapSpaceSize
* UsedHeapMemory

##### VifMetrics (for every virtual interface)
* rxBytes
* rxPacket
* txBytes
* txPacket

##### ZookeeperMetricsCollection (for every Zookeeper Node)
* ZKPacketsSent
* ZKPacketsReceived
* ZKAvgRequestLatency
* ZKNodeCount
* ZKWatchCount

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

<a name="metricquery"></a>
### Metric Query [application/vnd.org.midonet.MetricQuery-v1+json]

    POST    /metrics/query

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

<a name="metricqueryresponse"></a>
### Metric Query Response [application/vnd.org.midonet.MetricQueryResponse-v1+json]

    POST    /metrics/query

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

<a name="tenant"></a>
### Tenant [application/vnd.org.midonet.Tenant-v1+json]

    GET     /tenants
    GET     /tenants/:tenantId

Represents a tenant, or a group of users, in the identitity services.

<table>
    <tr>
        <th>Field Name</th>
        <th>Type</th>
        <th>POST/PUT</th>
        <th>Required</th>
        <th>Description</th>
    </tr>
    <tr>
        <td>id</td>
        <td>String</td>
        <td/>
        <td/>
        <td>ID of the tenant unique in the identity system.</td>
    </tr>
    <tr>
        <td>name</td>
        <td>String</td>
        <td/>
        <td/>
        <td>Name of the tenant in the identity system</td>
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
        <td>bridges</td>
        <td>URI</td>
        <td/>
        <td/>
        <td>A GET against this URI retrieves tenant's bridges.</td>
    </tr>
    <tr>
        <td>chains</td>
        <td>URI</td>
        <td/>
        <td/>
        <td>A GET against this URI retrieves tenant's chains.</td>
    </tr>
    <tr>
        <td>port_groups</td>
        <td>URI</td>
        <td/>
        <td/>
        <td>A GET against this URI retrieves tenant's port groups.</td>
    </tr>
    <tr>
        <td>routers</td>
        <td>URI</td>
        <td/>
        <td/>
        <td>A GET against this URI retrieves tenant's routers.</td>
    </tr>
</table>


#### <u>Query Parameters</u>

Query strings for Tenant may vary based on the Authtentication Service used.


**Keystone:**

<table>
    <tr>
        <th>Name</th>
        <th>Description</th>
    </tr>
    <tr>
        <td>marker</td>
        <td>
            ID of the last tenant in the previous search.  If this is
            specified, the GET returns a list of Tenants starting the next
            item after this ID.
        </td>
    </tr>
    <tr>
        <td>limit</td>
        <td>
            Number of items to fetch.
        </td>
    </tr>
</table>

<a name="traceconditions"></a>
### Trace Capture Conditions [application/vnd.org.midonet.Condition-v1+json]

    GET     /traceconditions/:conditionId
    POST    /traceconditions
    DELETE  /traceconditions/:conditionId

Trace condition specifies parameters for capturing traffic traces. You may
create and delete conditions. Update operation is not supported. Trace capture
condition contains the following fields:

<table>
    <tr>
        <th>Field Name</th>
        <th>Type</th>
        <th>POST/PUT</th>
        <th>Required</th>
        <th>Description</th>
    </tr>
    <tr>
        <td>conditionId</td>
        <td>UUID</td>
        <td>GET/DELETE</>
        <td>Yes</td>
        <td>A unique identifier of the resource.</td>
    </tr>
    <tr>
        <td>condInvert</td>
        <td>Bool</td>
        <td>POST</td>
        <td>No</td>
        <td>Invert the conjunction of all the other predicates</td>
    </tr>
    <tr>
        <td>dlDst</td>
        <td>String</td>
        <td>POST</td>
        <td>No</td>
        <td>The data link layer destination that this condition matches on. A
         MAC address in the form "aa:bb:cc:dd:ee:ff"</td>
    </tr>
    <tr>
        <td>dlSrc</td>
        <td>String</td>
        <td>POST</td>
        <td>No</td>
        <td>The data link layer source that this condition matches on. A MAC
         address in the form "aa:bb:cc:dd:ee:ff"</td>
    </tr>
    <tr>
        <td>dlType</td>
        <td>Short</td>
        <td>POST</td>
        <td>No</td>
        <td>Set the data link layer type (ethertype) of packets matched by
         this condition. The type provided is not checked for validity</td>
    </tr>
    <tr>
        <td>inPorts</td>
        <td>UUID</td>
        <td>POST</td>
        <td>No</td>
        <td>The list of ingress port UUIDs to match</td>
    </tr>
    <tr>
        <td>invDlDst</td>
        <td>Bool</td>
        <td>POST</td>
        <td>No</td>
        <td>Set whether the match on the data link layer destination should
         be inverted (match packets whose data link layer destination is NOT
         equal to dlDst). Will be stored, but ignored until dlDst is
         set</td>
    </tr>
    <tr>
        <td>invDlSrc</td>
        <td>Bool</td>
        <td>POST</td>
        <td>No</td>
        <td>Set whether the match on the data link layer source should be
         inverted (match packets whose data layer link source is NOT equal to
         dlSrc). Will be stored, but ignored until dlSrc is set</td>
    </tr>
    <tr>
        <td>invDlType</td>
        <td>Bool</td>
        <td>POST</td>
        <td>No</td>
        <td>Set whether the match on the data link layer type should be
         inverted (match packets whose data link layer type is NOT equal to
         the Ethertype set by dlType). Will be stored, but ignored until
         dlType is set</td>
    </tr>
    <tr>
        <td>invInPorts</td>
        <td>Bool</td>
        <td>POST</td>
        <td>No</td>
        <td>Inverts the in_ports predicate. Match if the packet's ingress is
         NOT in in_ports</td>
    </tr>
    <tr>
        <td>invNwDst</td>
        <td>Bool</td>
        <td>POST</td>
        <td>No</td>
        <td>Invert the IP dest prefix predicate. Match packets whose
         destination is NOT in the prefix</td>
    </tr>
    <tr>
        <td>invNwProto</td>
        <td>Bool</td>
        <td>POST</td>
        <td>No</td>
        <td>Invert the nwProto predicate. Match if the packet's protocol number
         is not nwProto</td>
    </tr>
    <tr>
        <td>invNwSrc</td>
        <td>Bool</td>
        <td>POST</td>
        <td>No</td>
        <td>Invert the IP source prefix predicate. Match packets whose source
         is NOT in the prefix</td>
    </tr>
    <tr>
        <td>invNwTos</td>
        <td>Bool</td>
        <td>POST</td>
        <td>No</td>
        <td>Invert the nwTos predicate. Match if the packet's protocol number
         is not nwTos</td>
    </tr>
    <tr>
        <td>invOutPorts</td>
        <td>Bool</td>
        <td>POST</td>
        <td>No</td>
        <td>Inverts the out_ports predicate. Match if the packet’s egress is
        NOT in out_ports</td>
    </tr>
    <tr>
        <td>invTpDst</td>
        <td>Bool</td>
        <td>POST</td>
        <td>No</td>
        <td>Invert the destination tcp/udp port range predicate. Match packets
         whose dest port is NOT in the range</td>
    </tr>
    <tr>
        <td>invTpSrc</td>
        <td>Bool</td>
        <td>POST</td>
        <td>No</td>
        <td>Invert the source tcp/udp port range predicate. Match packets whose
         source port is NOT in the range</td>
    </tr>
    <tr>
        <td>nwDstAddress</td>
        <td>String</td>
        <td>POST</td>
        <td>No</td>
        <td>The address part of the IP destination prefix to match</td>
    </tr>
    <tr>
        <td>nwDstLength</td>
        <td>Int</td>
        <td>POST</td>
        <td>No</td>
        <td>The length of the IP destination prefix to match</td>
    </tr>
    <tr>
        <td>nwProto</td>
        <td>Int</td>
        <td>POST</td>
        <td>No</td>
        <td>The Network protocol number to match (0-255)</td>
    </tr>
    <tr>
        <td>nwSrcAddress</td>
        <td>String</td>
        <td>POST</td>
        <td>No</td>
        <td>The IP address of the IP source prefix to match</td>
    </tr>
    <tr>
        <td>nwSrcLength</td>
        <td>Int</td>
        <td>POST</td>
        <td>No</td>
        <td>The length of the source IP prefix to match (number of fixed
         network bits)</td>
    </tr>
    <tr>
        <td>nwTos</td>
        <td>Int</td>
        <td>POST</td>
        <td>No</td>
        <td>The value of the IP packet TOS field to match (0-255)</td>
    </tr>
    <tr>
        <td>outPorts</td>
        <td>Array of UUID</td>
        <td>POST</td>
        <td>No</td>
        <td>The list of (interior or exterior) egress port UUIDs to match
        </td>
    </tr>
    <tr>
        <td>portGroup</td>
        <td>UUID</td>
        <td>POST</td>
        <td>No</td>
        <td></td>
    </tr>
    <tr>
        <td>tpSrc</td>
        <td>Range</td>
        <td>POST</td>
        <td>No</td>
        <td>A JSON representation of the Range object representing the
        TCP/UDP source port range to match, like {"start":80,"end":400}.
        When creating an ICMP condition, this field should be set to the ICMP
        type value. The absence of a Range will be interpreted as "any"</td>
    </tr>
    <tr>
        <td>tpDst</td>
        <td>Range</td>
        <td>POST</td>
        <td>No</td>
        <td>A JSON representation of the Range object representing the
        TCP/UDP source port range to match, like {"start":80,"end":400}.
        When creating an ICMP condition, this field should be set to the ICMP
        code value. A null value in this field will be intepreted as
        "any"</td>
    </tr>
    <tr>
        <td>uri</td>
        <td>URI</td>
        <td/>
        <td/>
        <td>A GET against this URI refreshes the representation of this
         resource</td>
    </tr>
</table>

<a name="systemstate"></a>
### System State [application/vnd.org.midonet.SystemState-v1+json]

    GET     /system_state
    PUT     /system_state

System State specifies parameters for the various states the deployment
might be in. You may modify the system state to make limited changes
to the behavior of midonet. For example, changing the "state" field to
"UPGRADE" will cause the spawning of new midolman agents to abort.

<table>
    <tr>
        <th>Field Name</th>
        <th>Type</th>
        <th>POST/PUT</th>
        <th>Required</th>
        <th>Description</th>
    </tr>
    <tr>
        <td>state</td>
        <td>String</td>
        <td>PUT</td>
        <td>yes</td>
        <td>Setting the state field to "UPGRADE" will put the midolman into
        'upgrade mode', which will cause all new midolman agents starting
        up in the deployment to abort the start up process. This is used
        during deployment wide upgrades to prevent unexpected startups of
        any midolman agent that might have the wrong version. This state can
        be reversed by setting the upgrade field to "ACTIVE". The deployment
        is not in upgrade state by default.</td>
    </tr>
    <tr>
        <td>availability</td>
        <td>String</td>
        <td>PUT</td>
        <td>yes</td>
        <td>Setting the availability to "READONLY" will cause most API
        requests to be rejected. The exceptions are only administrative
        APIs that don't affect the topology: system_state and write_version.
        This is meant to let the operator stop REST API requests while
        performing maintenance or upgrades. Setting the availability to
        "READWRITE" (the default value) allows both GETs and PUT/POST
        API requests.</td>
    </tr>
</table>

<a name="writeversion"></a>
### Write Version [application/vnd.org.midonet.WriteVersion-v1+json]

    GET     /write_version
    PUT     /write_version

Write Version specifies the version information that is relevant to the
midonet deployment as a whole. For example, the "version" field specifies
the version of the topology information that all midolman agents must write
to, regardless of that midolman agent's version.

<table>
    <tr>
        <th>Field Name</th>
        <th>Type</th>
        <th>POST/PUT</th>
        <th>Required</th>
        <th>Description</th>
    </tr>
    <tr>
        <td>version</td>
        <td>string</td>
        <td>PUT</td>
        <td>yes</td>
        <td>The version field determines the version of the topology data
        that the midolman agents will be writing. This matters during upgrade
        operations where we will change the write version only after all
        midolman agents are upgraded. The format of the version field is
        '[major].[minor]', where 'major' is the Major version, and 'minor'
        is the minor version. For example '1.2'.</td>
    </tr>
</table>

<a name="token"></a>
### Token [application/vnd.org.midonet.Token-v1+json]

A token represents the info required for the 'token authentication' method.
It can NOT be retrieved through a GET request, but instead must be retrieved
in the body or the header of a login request.

<table>
    <tr>
        <th>Field Name</th>
        <th>Type</th>
        <th>POST/PUT</th>
        <th>Required</th>
        <th>Description</th>
    </tr>
    <tr>
        <td>key</td>
        <td>string</td>
        <td></td>
        <td></td>
        <td>The authentication token</td>
    </tr>
    <tr>
        <td>expires</td>
        <td>string</td>
        <td></td>
        <td></td>
        <td>The expiration date for the authentication token</td>
    </tr>
</table>

<a name="hostversion"></a>
### Host Version [application/vnd.org.midonet.HostVersion-v1+json]

    GET     /versions

The Host Version specifies version information for each host running
in the Midonet deployment.

<table>
    <tr>
        <th>Field Name</th>
        <th>Type</th>
        <th>POST/PUT</th>
        <th>Required</th>
        <th>Description</th>
    </tr>
    <tr>
        <td>version</td>
        <td>string</td>
        <td></td>
        <td>yes</td>
        <td>the version of Midolman agent running on the host.</td>
    </tr>
    <tr>
        <td>hostId</td>
        <td>string</td>
        <td></td>
        <td>yes</td>
        <td>The the UUID of the host that the Midolman agent is running on.</td>
    </tr>
    <tr>
        <td>host</td>
        <td>string</td>
        <td></td>
        <td>yes</td>
        <td>The URI of the host that the Midolman agent is running on.</td>
    </tr>
</table>

<a name="resourcecollection"></a>
## Resource Collection

A collection of a resource is represented by inserting 'collection' right
before the resource name in the media type.  For example, to get a collection
of Tenants V1 you would represent:

*vnd.org.midonet.Tenant-v1+json*

as:

*vnd.org.midonet.collection.Tenant-v1+json*

See the Query Parameters section of each resource type whether the collection
can be filtered.

<a name="auth"></a>
## Authentication/Authorization

MidoNet API provides two ways to authenticate: username/password and token.
MidoNet uses [Basic Access Authentication] [1] scheme for username/password
authentication.  From the client with username 'foo' and password 'bar', the
following HTTP POST request should be sent to '/login' path appended to the
base URI:

    POST    /login
    Authorization: Basic Zm9vOmJhcg==

where <i>Zm9vOmJhcg==</i> is the base64 encoded value of 'foo:bar'.

If the API sever is configured to use OpenStack Keystone as its authentication
service, then the tenant name given in the web.xml file will be used in the
request sent to the keystone authentication service. However, you can override
this tenant name by specifying it in the request header.

    X-Auth-Project: example_tenant_name

The server returns 401 Unauthorized if the authentication fails, and 200 if
succeeds.  When the login succeeds, the server sets 'Set-Cookie' header with
the generated token and its expiration data as such:

    Set-Cookie: sessionId=baz; Expires=Fri, 02 July 2014 1:00:00 GMT

where 'baz' is the token and 'Wed, 09 Jun 2021 10:18:14 GM' is the expiration
date.  The token can be used for all the subsequent requests until it expires.
Additionally, the content type is set to a Token json type as such:

    Content-Type: application/vnd.org.midonet.Token-v1+json;charset=UTF-8

with the body of the response set to the token information:

    {"key":"baz","expires":"Fri, 02 July 2014 1:00:00 GMT"}

To send a token instead for authentication, the client needs to set it in
<i>X-Auth-Token</i> HTTP header:

    X-Auth-Token: baz

The server returns 200 if the token is validated successfully, 401 if the token
was invalid, and 500 if there was a server error.

For authorization, if the requesting user attempts to perform operations or
access resources that it does not have permission to, the API returns 403
Forbidden in the response.  Currently there are only three roles in MidoNet:

* Admin: Superuser that has access to everything
* Tenant Admin: Admin of a tenant that has access to everything that belongs
to the tenant.
* Tenant User: User of a tenant that only has read-only access to resources
belonging to the tenant.

Roles and credentials are set up in the auth service used by the API.

<a name="acronyms"></a>
## List of Acronyms

* API:  Application Programmable Interface
* BGP:  Border Gateway Protocol
* HTTP:  HyperText Transfer Protocol
* ICMP:  Internet Control Message Protocol
* JSON:  JavaScript Object Notation
* REST:  REpresentational State Transfer
* TOS:  Type Of Service
* URI:  Uniform Resource Identifier
* URL:  Uniform Resource Locator
* VIF:  Virtual Interface

[1]: http://tools.ietf.org/html/rfc2617
