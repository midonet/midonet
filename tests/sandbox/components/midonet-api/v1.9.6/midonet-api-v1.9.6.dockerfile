FROM sandbox/midonet-api:base
MAINTAINER MidoNet (http://midonet.org)

RUN apt-get install -qy --force-yes midonet-api=1.9.6 \
                                    python-midonetclient=1.9.6
