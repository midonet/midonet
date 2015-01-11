# Megaflow

Megaflow is an OpenVSwitch (OVS) feature than enables us to install flows
for which some fields are wildcarded (the input port field is never wildcarded,
as a totally wildcarded flow would be of little use). We do this by providing
OVS with a flow mask upon creating a new flow. A mask is comprised of a subset
of the flow's keys, where each field is either 0 or ~0, respectively specifying
a wildcard or an exact match; the absence of a mask key is considered a total
wildcard of all the fields in the flow's corresponding key.

We calculate the mask of a flow by keeping track throughout the simulation of
which fields have contributed to that outcome and which have not. This last
set of fields can be wildcarded while the previous requires an exact match. A
field is considered seen, and thus an exact match in the flow mask, when we
call the respective getter in the FlowMatch class. The mask is calculated by
the FlowMask class and is triggered by the Flow class, before we serialize it
to the datapath.

We don't store the flow mask in user
space: flow deletion is done by specifying the flow's specific keys. The OVS
module will apply all the masks it knows about to the specified flow and will
remove all flows that match the result. This is also how flow lookup is
performed. Note that the onus is on us to ensure we don't create overlapping,
conflicting wildcarded flows: no guarantees are given as to which flow gets
picked when different ones match an incoming packet.

We detect the version of the underlying OVS kernel module and suppress flow
masks if megaflow is not supported.