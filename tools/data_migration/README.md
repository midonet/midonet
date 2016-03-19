Upgrade
-------

1. update_from_neutron.py
Create tasks in midonet task table.  Nothing will happen yet.

2. zk_backup.py
Back up the ZK database.

3. zk_import_tasks.py
Download the midonet-cluster and midont-tools packages, set the 
midonet-cluster to task table mode and start it.  This will create 
the new topology in the ZK database.

4. package_update.py
Remove midonet-api.  Download the new midolman and python-midoclient 
packages. Update all of the API endpoints in /etc/neutron/plugin.ini 
and ~/.midonetrc to match port 8181.  Switch the midonet-cluster back 
to normal (non-task-DB) mode.  Restart neutron server.

Rollback
---------

1. zk_rollback.py
Restore the original ZK dump into the ZK database, overwriting any 
changes since the *prepare.py* script was called.  Remove all tasks
in the task table.

2. package_rollback.py
Remove midolman and python-midonetclient,  Download the old midolman
and python-midonetclient packages.  Revert all API endpoints in 
/etc/neutron/plugin.ini and ~./.midonetrc back to port 8080.  Restart
neutron server.
