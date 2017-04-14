## Wireshark dissectors for MidoNet

Example usage:

    % tshark -X lua_script:${DIR}/flowstate.lua -d udp.port==6677,vxlan -V

Alternatively you can put dofile("flowstate.lua") in your init.lua.

Note that these scripts are GPL-infected.
See https://wiki.wireshark.org/Lua#Beware_the_GPL
