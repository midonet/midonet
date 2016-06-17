## mm-ctl

When mm-ctl command processes bind/unbind port requests, it communicates
with the midolman instance running on the local host via a dedicated
unix domain socket, which is usually /var/run/midolman/midolman.sock.

Midolman provides a simple REST API on the socket.

|URI             |Method|Body                     |Description|
|:---------------|:-----|:------------------------|:----------|
|/binding/:portId|PUT   |{"interfaceName": <name>}|Bind the port with the named interface on the host|
|/binding/:portId|DELETE|None                     |Remove the binding for the portId|
