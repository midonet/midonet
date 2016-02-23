#!/bin/bash

echo "Provisioning l2gw interfaces..."

# Cleaning previous bridge if exists
sudo ip link set dev brl2gw down
sudo brctl delbr brl2gw

# Provisioning midolman trunk interfaces with tagged traffic
sudo pipework/pipework brl2gw -i l2gw0 mnsandboxmdts_midolman1_1 0/0 aa:bb:cc:00:01:02
sudo pipework/pipework brl2gw -i l2gw0 mnsandboxmdts_midolman2_1 0/0 aa:bb:cc:00:02:02

# Provisioning externalhost_1 untagged interfaces
sudo pipework/pipework brl2gw -i l2gw0 mnsandboxmdts_externalhost1_1 172.16.0.224/24 aa:bb:cc:01:03:01@10
sudo pipework/pipework brl2gw -i l2gw1 mnsandboxmdts_externalhost1_1 172.16.1.224/24 aa:bb:cc:01:03:02@20

# Provisioning externalhost_1 untagged interfaces
sudo pipework/pipework brl2gw -i l2gw0 mnsandboxmdts_externalhost2_1 172.16.0.225/24 aa:bb:cc:01:04:01@10
sudo pipework/pipework brl2gw -i l2gw1 mnsandboxmdts_externalhost2_1 172.16.1.225/24 aa:bb:cc:01:04:02@20


# Setting stp on
sudo brctl stp brl2gw on
