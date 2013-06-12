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



### Apache Lucene

Currently there is only one Searcher, org.midonet.search.lucene.LuceneSearcher,
which is implemented with [Apache Lucene][2], an open source search search
technology library.

TODO: Explain benefits of using Lucene


## Indexing

Searchable MidoNet resource fields are indexed.  These fields include the
unique resource ID, parent resource ID, names, tags, and others.  What to
index is controlled by <i>@index</i> annotation applied to getter methods
(search-related annotations are defined in org.midonet.search.annotation).

The names of the searchable fields are defined in
<i>org.midonet.search.lucene.SearchField</i> class as String constants.
They can be used in the annotation to indicate the name of for the search
field.


<pre><code>
class Foo {
    ...

    <b>@index(name=SearchField.FooName)</b> // Signals Searcher to index this
    public String getField() {
        return this.field;
    }

    ...
}
</code></pre>

<i>name</i> attribute indicates the value used to search for this field.
If <i>name</i> is missing, it assumes the name of the field.

DataClient class creates indices when resources are first created, removes
indices when resources are deleted, and updates indices when searchable
fields of resources are modified.

### Lucene Indexing

TODO: Go into details on how Lucene index data.


## Initialization

When the API server starts, it indexes all the ZooKeeper data.  If for some
reason the indexed data gets out of sync with the actual ZooKeeper data,
re-starting the API server re-syncs them.  Searcher has <i>init</i> interface
that lets the concrete class implement re-indexing.  <i>init</i> is called
once when the API server starts.  The API server is not available for service
until <i>init</i> completes successfully.


## Searching

Search interface defines a method to add a query value and to actually
perform search.  An example query may be:

<pre><code>
search.query(SearchField.TYPE, "foo").query(SearchField.NAME, "bar").search();
</code></pre>

Where SearchField.TYPE and SearchField.NAME represent the fields to search.


DataClient also exposes pagination feature using the <i>page</i> and
<i>limit</i> arguments.  <i>page</i> indicates the page number (starting with
1) to fetch, and <i>limit</i> indicates the number of items per page:

<pre><code>
search.query(SearchField.TYPE, "foo").page(10).limit(50).search();
</code></pre>


### Lucene Searching

TODO: Go into details of Lucene search implementation.


## Future Work

It is assumed that only one instance of API server runs at any time when the
search service is enabled.  This is because MidoNot currently does not have a
way to sync indices stored in multiple nodes.  This limitation will be addressed
in the future work.

[1]: http://www.atilika.com/
[2]: http://lucene.apache.org/