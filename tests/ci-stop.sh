#!/bin/bash

# Stop all conatiners so we don't have leaked processes
sudo sandbox-manage -c sandbox.conf kill-all
