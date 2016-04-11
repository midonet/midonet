======================
MidoNet Data Migration
======================

This is the MidoNet data migration tool.


Back Up Zookeeper Data
----------------------

Before data migration, make sure to back up your zookeeper data.
``zk_backup.py`` script is provided to dump the zookeeper data to a file::

    $ ./zk_backup.py -z 10.0.0.1:2181

Specify the zookeeper server with -z option in ``zk_host:zk_port`` format.
If this option is omitted, it defaults to ``127.0.0.1:2181``.

``zk_backup.py`` creates a zookeeper data dump file in the same directory that
you execute the script from, with the name ``zk_original.backup.<timestamp>``.

``zk_backup.py`` is just a wrapper for ``zkdump`` command, so ``zkdump`` must
already be installed on the host to be able to run ``zk_backup.py``.
