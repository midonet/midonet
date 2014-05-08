## OpenVSwitch integration tests

The Ovs test suite contains two test:
- OvsIntegrationTest
- OvsPacketReadThroughputTest

To run a test, after having compiled the necessary components, run maven with:

$ mvn exec:exec -pl odp -DodpTest=TestName

Before running, the environment network configuration needs to be changed
to allow the test to create the necessary resources in the kernel. Shell scripts
are provided in odp/src/main/resources for convience. These scripts go in pair
for setup and cleanup.
