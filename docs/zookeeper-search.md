# MidoNet ZooKeeper Data Search

## Abstract

This document describes the technical details of the design of the MidoNet
ZooKeeper data searching service.  This work was completed jointly by Midokura
and [Atilika] [1], a technology company based in Tokyo, Japan, with expertise in
the field of data search.


## Motivation

MidoNet stores all of its state in ZooKeeper, but ZooKeeper does not provide any
sophisticated search mechanism for its data.  It simply lets you get the data of
one node, or get the child nodes under one node.  This is clearly not sufficient
to implement API features like advanced data search, sorting of searched data
and pagination.  MidoNet search service makes possible searching data stored in
ZooKeeper to make possible richer API features.


## Introduction

In order to implement searching service in MidoNet API, which includes not only
searching for the stored data but also pagination and sorting, a middle layer
that provides these features must exist between ZooKeeper and the API.  The
middle layer is responsible for indexing the fields to search on when resources
are created or udpated, cleaning up the indices when resources are deleted, and
to provide API to fetch the resource data using the index.  The middle layer is
implemented in org.midonet.search package.


## Search Package

org.midonet.search package contains the code for the search service.  It
contains the ZkSearcher interface.  This interface define methods to
index and search MidoNet resources.  After implementing the interface, to
specify the concrete ZkSearcher classes to be used in MidoNet API,
configure web.xml as follows:

<pre><code>
&lt;context-param&gt;
  &lt;param-name&gt;zookeeper-searcher&lt;/param-name&gt;
  &lt;param-value&gt;
      <b>org.midonet.search.lucene.LuceneZkSearcher</b>
  &lt;/param-value&gt;
&lt;/context-param&gt;
</code></pre>

In the above example, <b>org.midonet.search.lucene.LuceneZkSearcher</b> is a
concrete class that implements ZkSearcher interface.

TODO: Go into more details on how the interfaces are designed (builder pattern
with ZkSearcher?)  Also about indexAll() method.

* searcher.type("router").query("field1", "foo").query("field2", "bar").search()

Where "field1" and "field2" are the annotated string constants in the resource
class on the fields that you want to index, and "foo" and "bar" are the values
to search.


### Apache Lucene

Currently there is only one set of Indexer and Searcher implemented,
org.midonet.search.lucene.LuceneIndexer and
org.midonet.search.lucene.LuceneSearcher, both implemented with
[Apache Lucene][2], an open source search search technology library.

TODO: Explain benefits of using Lucene


## Indexing

Searchable MidoNet resource fields are indexed.  These fields include the
unique resource ID, parent resource ID, names, tags, and others.  What to
index is controlled by <i>@index</i> annotation applied to getter methods
(search-related annotations are defined in org.midonet.search.annotation).

<pre><code>
class Foo {
    ...

    <b>@index(k="")</b>      // Signals Indexer to index this field
    public String getField() {
        return this.field;
    }

    ...
}
</code></pre>



DataClient class creates indices when resources are first created, removes
indices when resources are deleted, and updates indices when searchable
fields of resources are modified.

All the ZooKeeper data is indexed when the API server starts.

### Lucene Indexing

TODO: Go into details on how Lucene index data.


## Searching


### Lucene Searching

TODO: Go into details of Lucene search implementation.

## Future Work

It is assumed that only one instance of API server runs at any time when the
search service is enabled.  This is because MidoNot currently does not have a
way to sync indices stored in multiple nodes.  This limitation will be addressed
in the future work.

[1]: http://www.atilika.com/
[2]: http://lucene.apache.org/