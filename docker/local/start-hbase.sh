#!/bin/sh
# Local HBase: advertise "host.docker.internal" to clients, but bind to this container's own IP.
# Inside the container that name must resolve to the container IP, otherwise HBase binds to the wrong address.
cp /tmp/site.xml /hbase/conf/hbase-site.xml
IP=$(hostname -i | awk '{print $1}')
grep -v "host.docker.internal" /etc/hosts > /tmp/hosts.new
echo "$IP host.docker.internal" >> /tmp/hosts.new
cat /tmp/hosts.new > /etc/hosts
exec /entrypoint.sh
