======================
MidoNet Data Migration
======================

This is the MidoNet data migration tool.

Upgrade
-------

1. migration_prepare.py (command: ``./update_from_neutron.py``)
Prepare for the migration by inspecting the current topology and gathering
data.  This will also create tasks in midonet task table for any
neutron-based objects that will be migrated from the old MidoNet topology to
the new one (note that this will not actually execute the tasks, only add
them to the task table in preparation for the actual migration).  Other data
files will be created for MidoNet object (i.e. not created via Neutron) to
be re-created once the migration phase has begun (again, no topology changes
will be executed via this utility).


2. zk_backup.py (command: ``./zk_backup.py``)
Back up the ZK database by running:

::

  zk-dump -z <zk server> -d -o ./migration_data/zk_original.backup

The ``./migration_data/zk_original.backup`` file will hold the backed up
ZooKeeper topology, so please treat it with care!

The ``zk_server`` parameter is calculated from your environment/config files.


3. zk_import_tasks.py (command: ``./zk_import_tasks.py``)
Download the midonet-cluster and midonet-tools packages, set the
midonet-cluster to task table mode and start it.  This will create
the new topology in the ZK database.

This is achieved by running the ``mn-conf set`` command based on settings
stored in ``./migration_data/mn-conf.settings`` during migration.  These
settings typically look like:

::

  cluster {
    neutron_importer {
      enabled: true
      connection_string: "jdbc:mysql://localhost:3306/neutron"
      user: [db_user from neutron.conf]
      password: [db_password from neutron.conf]
    }
  }


4. package_update.py (command: ``./package_update.py``)
This script will remove the midonet-api package, download the new midolman
and python-midonetclient packages (currently from the internal Artifactory
server) (currently the latest 5.X packages), update all of the API endpoints
in /etc/neutron/plugin.ini and ~/.midonetrc to port 8181 (please change
this value if your desired API port is different from the default), switch
the midonet-cluster back to normal (non-task-DB) mode via ``mn-conf``, and
finally restart the neutron server.  Updating the neutron.conf to match the
new versions is up to the user.


Rollback
--------

1. zk_rollback.py
Restore the original ZK dump into the ZK database, overwriting any
changes since the *prepare.py* script was called with the command:

::

  zk-dump -z <zk server> -l -i ./migration_data/zk_original.backup

, and remove all tasks in the task table (via direct ``mysql`` commands).
This removal is (and must be) done in such a way that the task ID fields do
*not* get reset, as the task importer will expect the ID to be glboally
 increasing.

2. package_rollback.py
Remove midolman and python-midonetclient, download/install the old midolman
and python-midonetclient packages, revert all API endpoints in
/etc/neutron/plugin.ini and ~./.midonetrc back to port 8080 (please change
this port to whatever port you used to use if it is different from the
default), and finally restart the neutron server.
