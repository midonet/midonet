# MidoNet API: Authentication and Authorization

## Motivation

Through the MidoNet API, you can set ownership to MidoNet resources such as
routers, bridges and chains, by supplying the owner ID when creating them (the
field is named 'tenantId' for legacy reasons).  MidoNet, however, does not
provide ways to manage these owners.  That is, it does not offer API to create
or delete owners.  The owner ID is simply a string representation of a unique
identifier of a user managed in an external service that MidoNet is integrated
with, such as OpenStack and CloudStack.  These external services are typically
cloud orchestration platforms, and they have their own identity services.
To integrate MidoNet API with these cloud orchestration services, it is
required that the API integrates with their identity services.

For every HTTP request that comes to MidoNet API, it must perform authentication
(validating the user making the call) and authorization (checking the access
level of the user making the call).  Because MidoNet does not have records of
these users in its data store (since it does not manage them itself), it must
ask the external identity services to perform the user validation and to provide
the user privilege information.

This document attempts to describe the current design of MidoNet API that allows
you to plug in different authentication/authorization (AuthN/AuthZ) mechanisms
with simple configuration setting.  Note that all the classes mentioned exist
under the package 'org.midonet.api.auth' unless mentioned
otherwise.


## Unique user identifier('token') in the HTTP header

To validate a user making the API request, the MidoNet API needs to be provided
a token(or a key) in the request that can be used to uniquely identify the user.
'X-Auth-Token' HTTP extension header is used for this purpose.  The calling user
must set this header field to the user identifier string.  Not supplying this
identifier results in the API responding with 401 UNAUTHORIZED status.

## UserIdentity

UserIdentity class represents a generic user in MidoNet that is making the API
request.  The member fields include information about the user, such as its ID,
name, roles, and the unique identifier('token') used to validate the user.  How
this object is used for authentication and authorization is explained in the
sections below.

## Token

Token class represents a generic token in MidoNet.  When a user logins into the
external auth service using its username and password credentials, MidoNet
returns a Token object to the user, which contains 'key' that is used to
generate subsequent API requests, and 'expires' which indicates the time to
which the token is valid until.

# Roles

MidoNet API implements Role-Based Access Control (RBAC) mechanism to perform
authorization. Defined in AuthRole class, the roles in MidoNet are:

 * <b>Admin</b>:  The root administrator of the system.  The users with this
 role are allowed to do all operations.
 * <b>TenantAdmin</b>: The users with this role have write and read access to
 their own resources.
 * <b>TenantUser</b>: The users with this role only have read access to their
 own resources.

Because the roles defined in the external identity services may not match the
roles in MidoNet, when the UserIdentity object is created during the
successful attempt at authentication, the roles of this user from the external
services must be converted to the roles in MidoNet.  This conversion is one of
the responsibilities of the auth provider class described in the section below.


## Authentication service

Authentication service is a class that implements AuthService interface.  In
the current version of MidoNet API, it defines two methods:

<pre><code>
  public UserIdentity getUserIdentityByToken(String token) throws AuthException;

  public Token login(String username, String password,
                     HttpServletRequest request) throws AuthException;
</code></pre>

The implementation of 'getUserIdentityByToken' method must, with a given token,
instantiate a UserIdentity object with all its member fields set properly if the
authentication succeeds, return null if the user is not valid, or throw
AuthException if an unexpected error occurs during authentication.  The roles of
the user are also converted from the roles defined in the external identity
service to the those understood by MidoNet.

The implementation of 'login' method must authenticate the user using the given
username and password credentials (and any item from HttpServletRequest object
if necessary) with the auth service.  It should return a Token object if the
authentication succeeds, null if the authentication fails, and throw an
exception if there was an error.

The authentication provider can be configured in web.xml's 'context-param'
element as follows:

<pre><code>
  ...
  &lt;context-param&gt;
    &lt;param-name&gt;auth-auth_provider&lt;/param-name&gt;
    &lt;param-value&gt;
      org.midonet.api.auth.MockAuthService
    &lt;/param-value&gt;
  &lt;/context-param&gt;
  ...
</code></pre>

For the value, specify the fully qualified name of the class that implements
<i>org.midonet.api.auth.AuthService</i> interface.  In the example above,
<i>org.midonet.api.auth.MockAuthService</i> class is specified which is a
service that provides as a way to mock the auth service for testing or
disabling auth.  See the 'Mocking auth service' section below for more details.
For authentication with OpenStack Keystone, specify
<i>org.midonet.api.auth.keystone.KeystoneService</i>.

As mentioned in the previous section, the auth service must convert the roles
in the external service (like OpenStack Keystone) to those in MidoNet.  It
relies on the entries specified in web.xml to determine the role mapping:

<pre><code>
  ...
  &lt;context-param&gt;
    &lt;param-name&gt;auth-admin_role&lt;/param-name&gt;
    &lt;param-value&gt;mido_admin&lt;/param-value&gt;
  &lt;/context-param&gt;
    &lt;context-param&gt;
      &lt;param-name&gt;auth-tenant_admin_role&lt;/param-name&gt;
      &lt;param-value&gt;mido_tenant_admin&lt;/param-value&gt;
  &lt;/context-param&gt;
  &lt;context-param&gt;
    &lt;param-name&gt;auth-tenant_user_role&lt;/param-name&gt;
    &lt;param-value&gt;mido_tenant_user&lt;/param-value&gt;
  &lt;/context-param&gt;
  ...
</code></pre>

In the above example, mido\_admin, mido\_tenant\_admin,
and mido\_tenant\_user roles stored in the external service are translated to
admin, tenant\_admin, and tenant\_user in Midonet, respectively.


## Servlet filter (AuthFilter and LoginFilter)

Authentication is implemented as a servlet filter.  AuthFilter class is
registered as a servlet filter at the start of the MidoNet API service.  The
responsibilities of this class are loading the appropriate authentication
provider configured in web.xml, and using the provider to authenticate the user
with the token passed in the HTTP header.  If the validation succeeds, it sets
the newly instantiated UserIdentity object as the value of 'UserIdentity'
attribute in the HttpServletRequest object.  By doing so, the UserIdentity
object becomes accessible by the API handler methods to perform additional
authorization checks.  If the authentication fails, it responds with 401
UNAUTHORIZED.

LoginFilter is also a servlet filter, and it is applied to requests that are
destined to '&lt;root_uri&gt;/login' URI.  AuthFilter is not applied for this
URI, and LoginFilter is not applied for al the other requests.  Thus, there is
never a case in which both of these filters are applied simultaneously.
LoginFilter is a convenience filter that allows externals clients of MidoNet
to 'login' using username and password, and let MidoNet handle the generation
of session tokens that can be used for subsequent requests until they expire.
Behind the scene, MidoNet is actually logging in to the external auth system
on behalf of the user.  The username and password are sent using HTTP's Basic
Access Authentication header as such:

<pre><code>
Authorization: Basic &lt;Base64 encoded value of 'username:password'&gt;
</code></pre>

The response to this request, if successful, would set the cookie of
the response as such:

<pre><code>
Set-Cookie: &lt;cookie value&gt;; Expires=&lt;expiration date in GMT&gt;
</code></pre>

Where the cookie value contains the string that should be used for API
request, and the 'Expires' is set to the token expiration time with the format,
"Wdy, DD Mon YYYY HH:MM:SS GMT".

When an invalid or expired token is sent to the API, the filter responds
with 'WWW-Authenticate' header set to 'Basic MidoNet' to indicate a
challenge to the client to go through Basic HTTP authentication with
username/password credentials to obtain the token.

Note that, in addition to basic authentication header, in some authentication
systems, it may require that the client send additional data in the HTTP
extension header.  The only authentication system that requires an
additional header value is Keystone, in which it needs 'X-Auth-Project'
to be set to the tenant name in Keystone.

## Container request filter (AuthContainerRequestFilter)

AuthContainerRequestFilter implements ContainerRequestFilter interface, which
is a Jersey framework request filter that provides access to various request
objects such as HttpServletRequest.  As explained in the section above,
HttpServletRequest is the object in which the UserIdentity object is set by the
servlet filter.  In AuthContainerRequestFilter, a SecurityContext object is
instantiated (explained in more detail below) and made available to the API
resource classes that will perform authorization checks.

## SecurityContext

SecurtiyContext is an injectable interface that provides access to security
related information.  This interface is described in detail in the
[JSR-311 API page] [1].

In MidoNet API, UserIdentitySecurityContext class implements SecurityContext,
and it is injected into the API handler methods.  This object is used for
authorization.  UserIdentitySecurityContext is instantiated in
AuthContainerRequestFilter's filter method.  It takes in UserIdentity as its
only argument in the constructor, and relies on the roles set in UserIdentity
to perform authorization checks.

## Authorization

Authorization is broken up into two parts:  Authorizing the access to particular
method, and authorizing the action performed on a particular object.

### Authorizing access to methods

RolesAllowed annotation is used to indicate which roles are allowed to access
methods.  The use of RolesAllowed annotation is described in the JSR-250
specification.

An example would look as follows:

<pre><code>

  @RolesAllowed({ AuthRole.ADMIN, AuthRole.TENANT_ADMIN })
  public Response create(Port port) {
      ...
  }

</code></pre>

In the example above, only users with Admin and TenantAdmin roles are allowed
to access this method.  403 FORBIDDEN is returned if the user is not authorized.

### Authorizing an action on an object

Each API resource has a concrete Authorizer<T> class that implements the
following method:

<pre><code>
    public boolean authorize(SecurityContext context, AuthAction action, T id)
            throws StateAccessException;
</code></pre>

where <i>context</i> is the SecurityContext object that provides access to the
user security information, including the roles, <i>action</i> is AuthAction enum
that can either be 'Read' or 'Write', and <i>id</i> is the ID of the resource
object to authorize against.  This method returns true if the authorization is
successful, and false otherwise.  403 FORBIDDEN is returned if the user is not
authorized.

## Mocking auth provider

MockAuthService is a helper class that lets you mock the auth provider.  In
web.xml, you can specify custom tokens and their associated roles.  By making
requests with these tokens, you can test the API with a mocked user with various
privilege levels.

<pre><code>
  ...
  &lt;context-param&gt;
    &lt;param-name&gt;auth-admin_token&lt;/param-name&gt;
    &lt;param-value&gt;1111&lt;/param-value&gt;
  &lt;/context-param&gt;
  &lt;context-param&gt;
    &lt;param-name&gt;auth-tenant_admin_token&lt;/param-name&gt;
    &lt;param-value&gt;1111&lt;/param-value&gt;
  &lt;/context-param&gt;
  &lt;context-param&gt;
    &lt;param-name&gt;auth-tenant_user_token&lt;/param-name&gt;
    &lt;param-value&gt;1111&lt;/param-value&gt;
  &lt;/context-param&gt;
  ...
</code></pre>

In the example above, setting 'X-Auth-Token' to '1111' instructs MockAuthService
to instantiate a UserIdentity object with roles set to all of 'Admin',
'TenantAdmin' and 'TenantUser'.  Also note that the authentication always
succeeds when using MockAuthService.

[1]: http://jsr311.java.net/nonav/javadoc/javax/ws/rs/core/SecurityContext.html
