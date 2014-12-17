## Midonet CLI

### Overview

This document describes the design of Midonet's CLI. The CLI is a command
interpreter built on top of Midonet's REST API, it lets users explore and
manipulate Midonet's virtual topology.

The main requirements/features are:

1. Offer full access to the API's functionality.
2. A friendly syntax model on iproute.
3. Easily discoverable through autocompletion, a 'describe' command and
   comprehensive help.
4. Basic scriptability
5. Seamlessly hiding the use of UUIDs in interactive sessions:
  * The first time an object is shown or listed as a result of a command an
    alias is automatically generated for it. Alias are chosen using a prefix
    for each different object type and adding an incremental counter (see
    below), the counter is scoped to the namespace where the alias was created.
  * Each object has its own alias namespace for its children
  * Object ids are not printed unless the user specifically asks that the id field
    is shown.
  * Anywhere an object is referenced in a command, it can be referred to by alias
    or by uuid, interchangeably.

A sample CLI session:

```
midonet> list bridge
bridge bridge0 name new-bridge
midonet> bridge bridge0 list port
port port0 type ExteriorBridge device bridge0
port port1 type ExteriorBridge device bridge0
midonet> list router
router router0 name new-router
midonet> router router0 list port
midonet> router router0 add port type InteriorRouter address 192.168.0.1 net 192.168.0.0/24
midonet> router router0 list port
port port0 type InteriorRouter device router0 mac 02:76:61:ed:1a:75 address 192.168.0.1 net 192.168.0.0/24
midonet> bridge bridge0 show port port0
port port0 type ExteriorBridge device bridge0
```

Alias within a namespace can be accessed hierarchically if they need to be used
outside their parent namespace:

```
midonet> host host0 add port interface eth0 port bridge0:port0
```

Currently, the alias manager keeps uuids only so when you need the whole object
you are out of luck and you need to specify in what collection to find 'bridge0'
and then, within bridge0, where to find port0. Thus, you have to write:

```
midonet> bridge bridge0 show port port0
```

...but cannot write:

```
midonet> show bridge0:port0
```

### Object model

The CLI framework provides an object model that allows the mapping of CLI types,
attribute names and operations, to Midonet API objects and methods.  The object
model let CLI commands (explained below) work generically on any type of CLI
object without writing custom code. Thus, when a new object type is added to the
API, defining its mapping to the CLI object model should suffice to have the
existing CLI commands work on it.

An API object mapping is a class deriving from 'ObjectType'. The base class
contains all the methods needed by the engine to discover and navigate the
fields and collections the type contains. All the subclass has to do is override
the constructor, where it defines the attributes for the wrapped API object and
the names of the API methods that would modify them.

This the ```Bridge``` class:

```python
class Bridge(ObjectType):                                                         
    def __init__(self, obj):                                                      
        ObjectType.__init__(self, obj)                                            
        self.put_attr(TenantAttr())                                               
        self.put_attr(Collection(name = 'port',                                   
                                 element_type = BridgePort,                       
                                 list_method = 'get_ports',                       
                                 getter = 'get_port',                             
                                 factory_method = 'add_port'))           
        self.put_attr(SingleAttr(name = 'name',                                   
                                 value_type = StringType(),                       
                                 setter = 'name',                                 
                                 getter = 'get_name'))                            
        self.put_attr(SingleAttr(name = 'infilter',                               
                                 value_type = ObjectRef('chain'),                 
                                 getter = 'get_inbound_filter_id'))               
        self.put_attr(SingleAttr(name = 'outfilter',                              
                                 value_type = ObjectRef('chain'),                 
                                 getter = 'get_outbound_filter_id'))              
                                                                                  
    def type_name(self):                                                          
        return "bridge"
```

### Parser / engine

Each line of input is a command. And the string is tokenized with python's
shlex module, in other words, using usual sh rules.

All the available commands supply a list of patterns that they support. A
pattern is a list of tokens. The patterns are tried in order, the first
complete match on the tokenized input wins. The collection of all command
patterns conforms the CLI syntax.

This is the process to match a pattern:
  1. A MatchingContext is created. A MatchingContext is an immutable object
     whose purpose is to facilitate the backtracking and the accumulation of
     state/context. It contains:
     * the current context objects on which to perform children lookups
     * the list of yet-to-be-consumed input words,
     * a list of 'parsed tokens' which is just a list of whatever each matched
       token decides to add in there.
     * A copy() method to create an incrementally modified context for the next
       step.

  2. Each token in the pattern is asked if it matches on the current
     MatchingObject. If successful, it will return a new MatchingObject with
     accumulated state, and the parser will move on to the next token. If
     unsuccessful, matches() returns a ParsingSubmatch, which contains the
     furthest MatchingContext until a match failed and will later be used to
     decide which syntax error to report if no pattern matches the input. This
     mechanism is also used to implement autocompletion (see below).

  3. What each token class does in its matches() method is usually totally
     context dependent and involves stuff like checking if the object in context
     contains a certain field, resolving aliases, etc. They are also responsible
     of making sure that the 'parsed token' context piece will end up holding
     everything needed to execute the command.

To sum up, a token, which extends from CommandToken, needs to implement matches()
and complete(). There are a few composition token subclasses: Or, ZeroOrOnce,
OnceOrMore, ZeroOrMore, etc. ...and a few terminal tokens (although not in the
strict sense of terminal, they may consume several arguments): ObjectById, Verb,
etc. A new command will usually construct its patterns based solely on these
framework provided CommandToken subclasses, although it is free to mix in its
own custom token types.

Once we have a match, the command is run. Commands extend from 'Command' and
they just need to implement do() and help(). The do() method will receive the
latest MatchingContext from the pattern that matched, and inside it should find
all the accumulated context it needs to run, such as the object on which to act.

Autocompletion works similarly, the parser calls complete() on the first
non-matching token of the pattern. The difference here is that all paths are
tried and the results are accumulated. At the end of the process duplicates
are removed and the remaining list of completions is offered to the user.

### AliasManager

It's an interface around a tree of alias namespaces (one for each object
discovered). Globally accessible because it's needed almost everywhere.
