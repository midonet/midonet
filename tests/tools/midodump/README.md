midodump.py
===========

`midodump.py` is a sript to dump the data on our NSDB through the API as a YAML
file which can be loaded by the virtual topology manager of
[MidoNet Distributed Testing System][1].

[1]: https://github.com/midokura/qa/tree/master/mdts

Prerequisites
-------------

midodump depends on [PyYAML][2] for (de)serialize and [NumPy][3] for the basic
linear algebra to handle links among ports appropriately. You can install them
with the following command:

```bash
$ pip install -r pip_requiements.txt
```

It also depends on [python-midonetclient][4]. Please follow the instruction of
the package to install it.

[2]: http://pyyaml.org/
[3]: http://www.numpy.org/
[4]: https://github.com/midokura/python-midonetclient

Usage
-----

You need to specify the URL of the MidoNet API and the tenant name. The
multiple tenants are not supported in the YAML format for now. As an additional
option, you can specify the name of the output file, which defaults to 
`topology-YYYYmmdd-HHMMSS.yaml`

### Examples

```bash
$ sh -c 'cd /opt/qa/tools/midodump && \
  PYTHONPATH=$(pwd):$(pwd)/../:$(pwd)/../../  python midodump.py \
  --midonet_url http://127.0.0.1:8080/midonet-api
  --tenant_id 8a606b626f944cb098093f62ae572093
  -o default-topology.yaml'
```

```bash
$ sh -c 'cd /opt/qa/tools/midodump && \
  PYTHONPATH=$(pwd):$(pwd)/../:$(pwd)/../../  python midodump.py \
  --midonet_url http://127.0.0.1:8080/midonet-api
  --tenant_id 8a606b626f944cb098093f62ae572093
```

load_topology.py
=================

`load_topology.py` is a script to load the YAML file exported by `midodump.py`.

Usage
-----

It requires a YAML file which should be exported by `midodump.py` as an
argument.

### Examples

```bash
$ sh -c 'cd /opt/qa/tools/midodump && \
  PYTHONPATH=$(pwd):$(pwd)/../:$(pwd)/../../ python load_topology.py \
  default-topology.yaml'
```

