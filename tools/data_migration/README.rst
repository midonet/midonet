======================
MidoNet Data Migration
======================

This is the MidoNet data migration tool.

Currently data migration assumes that the Neutron version is kilo.


How to Run
----------

Run the following command to do a dry-run of the data migration::

     $ ./migrate.py --dryrun

This command outputs the list of tasks that would be performed in order for
data migration.  Currently only dry-run is supported.

To turn on debugging::

     $ ./migrate.py --debug

For more information about the command::

     $ ./migrate.py --help
