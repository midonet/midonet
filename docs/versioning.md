## Midonet Upgrade Need-to-know Information

From the developer's point of view, there are only three things to know,
and they are all related to the adding or removing of fields from the DTOs:

1. Usage of the 'Since' annotation.
    * This annotation is used by the VersionAnnotationIntrospector to
      filter out fields in Dto's when serializing and deserializing.
    * In short, you put this annotation above new fields you introduce,
      and anytime Midonet is writing a previous version of this Dto,
      that field will not be included.
    * For example, if you put a new field in version 1.2 called "numPorts"
      in Router, you would put the Since annotaion above that field:

```java

        @Since("1.2")
        int numPorts;

```

      Anytime Midonet is serializing the Router object with version 1.1,
      "numPorts" won't be included.

2. Usage of the 'Until' annotation.
    * This annotation works exactly like the 'Since' annotation, except
      the field will be filtered out for versions _higher_ than the
      version given.
    * For example, if you remove a field called "numPorts" in version 1.4,
      you would annotate like this:

```java

        @Until("1.3")
        int numPorts;

```

      Anytime Midonet is serializing the Router object with version 1.3,
      "numPorts" won't be included.
    * Both annotations can be used on the same field

```java

        @Since("1.2")
        @Until("1.3")
        int numPorts;

```

3. Responsibility to ensure backward compatability
    * Onus is on the developer making changes to ensure backward
      compatability. the 'Since' and 'Until' annotations are just ways
      of making sure serialization is happening according to version.
    * This means, for example, that if you add a field to some Dto
      you need to keep in mind that this field won't necessarily be
      available in ZK. This means that you need to make sure Midonet
      can not break if that field is null.
    * Deleting fields need the most caution, because if you delete a
      field, then you need to also make sure that the _previous_ version
      of Midonet won't break without that field.

## Implementation Details

- The below are notes on implementation details for information only.
  They don't define any policies that the developers need to be aware of
  when making changes between versions.
- For overall design and requirements, please check out the
  [upgrade](upgrade.md) document.

The Midonet Upgrade Work has five main parts:
1. Annotations - means for developers to define which fields
        go in which versions
2. VersionCheckAnnotationIntrospector - filtering plugin to the jackson JSON
        serializer that will filter out fields to exclude.
3. VersionCheckObjectMapperFactory - used to provide different ObjectMapper
        objects for different versions.
4. "write_version" node in ZK - used for midonet to know which version
        it should be serializing DTOs into.
5. host version node in ZK - used by the coordinator

### More details on the items:

1. Annotations
    * The Since and Until annotations use 'double' as their value.
      This allows the definition of a Major and Minor version.

2. VersionCheckAnnotationIntrospector
    * plugin for the Jackson serializer/deserializer to customize
      how we filter fields based on Annotations.
    * This extends NopAnnotationIntrospector because there are
      quite a few functions we don't use. NopAnnotationIntrospector
      fills those functions out with No-ops.

3. VersionCheckObjectMapperFactory
    * This allows the API server to deserialize/serialize based on the version.
    * The API server can extract the requested version based on the media type
      sent by the client, and get the appropriate AnnotationIntrospector.

4. "write_version" node in ZK
    * This is the node that tells the entire Midonet deployment which version
      we are writing to ZK, regardless of our running version.
    * Writing in an older version should not adversely affect Midonet. We will
      have written any new features in such a way that they will not break if
      we are using older DTOs.
    * we can NOT guarantee forward compatibility. Therefore, if any Midonet
      agent comes up with an older version than what is in the "write_version"
      node, then the Midonet Agent will abort startup.
    * The write_version is checked before each write to zookeeper, so that all
      writes are up-to-date on which write version should be used.
    * The write_version node can have its value changed by the administrator
      or a script we write to manage the upgrade process. To
      protect against race conditions (ie a 1.0 node coming up just as we
      change the write version to 1.1), we have a "system_state" node to use
      as a check. if "system_state" is set to "UPGRADE", then any node trying
      to come up during that time will abort startup. So, in order to ensure
      no race conditions when changing the value of write_version, the
      administrator first sets system_state to UPGRADE, then sets write_version
      to the new version, then changes system_state back to RUN.

5. host version node in ZK
    * Every host will put their version in ZK as an Ephemeral node (meaning a
      node that is automatically deleted if the ZK connection goes down.) This
      information will be used by the upgrade coordinator to check which nodes
      are running which version. The idea is that once all nodes are in the
      correct version, the write_version can be changed to the correct version
      and upgrade will be condidered complete.

### TODO Work Items (not needed in the initial version).

* Because the current version is the first upgradeable version, it is not
  necessary to make code changes required to make it work with previous
  versions.
* Items required for the next version:
  1. Coordinator stand-alone program to continuously check the status
      of the host versions, and change the "write_version" node once
      all of the nodes are done.
  2.  Watcher for the "write_version" in the Midonet Agent to change
      Serialization Versions.

