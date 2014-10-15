#!/usr/bin/perl

=head1 NAME

api-client.pl - test client for the Midonet Brain API

=head1 SYNOPSIS

api-client.pl [-s server]

=head1 DESCRIPTION

Opens a communication channel with the Midonet Brain API. server can be
in the form of "host:port" for plain connections or "ws://host:port/websocket"
for websocket connections.

This script sends a protobuf, and expects to receive something back.

=head1 REQUIREMENTS

=over

=item Google::ProtocolBuffers

L<http://search.cpan.org/perldoc?Google%3A%3AProtocolBuffers>

=item Protocol::WebSocket

L<http://search.cpan.org/perldoc?Protocol%3A%3AWebSocket>

=back

=head1 COPYRIGHT

Copyright 2014 Midokura SARL

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.

=cut

use strict;
use warnings;

use Getopt::Long;
use Cwd qw(abs_path);
use FindBin qw($Script $RealBin);
use IO::Handle;
use Socket;
use Fcntl;
use Errno qw(:POSIX);

use Google::ProtocolBuffers;
use Protocol::WebSocket::Client;

STDOUT->autoflush(1);
STDERR->autoflush(1);

my $DEFAULT_HOST = "localhost";
my $DEFAULT_PORT = 8081;
my $DEFAULT_WS_PORT = 8080;

my $SERVER = "${DEFAULT_HOST}:${DEFAULT_PORT}";
GetOptions(
    'server|s=s' => \$SERVER,
);

my $PROTOSPECDIR = "$RealBin/../../../cluster/src/main/proto";
my @PROTOSPECS = glob("${PROTOSPECDIR}/*.proto");
die "No protocol buffer specifications available\n" if ($#PROTOSPECS < 0);

my %PROTO_OPTS = (
    include_dir => $PROTOSPECDIR,
    create_accessors => 1,
    follow_best_practice => 1,
);
foreach my $SPEC (@PROTOSPECS) {
    Google::ProtocolBuffers->parsefile($SPEC, \%PROTO_OPTS);
}

my $proto_uuid = Org::Midonet::Cluster::Models::UUID->new(
    {msb => 0, lsb => 1}
);

sub socket_write($$) {
    my ($sock, $buffer) = @_;
    my $size = length($buffer);
    my $off = 0;
    while ($size > 0) {
        my $bytes = syswrite($sock, $buffer, $size, $off);
        if (defined($bytes) && $bytes <= $size) {
            $off += $bytes;
            $size -= $bytes;
        } elsif  (!defined($bytes) && $! != EAGAIN) {
            die "socket write error: $!\n"
        }
    }
}

sub socket_read($$) {
    my ($sock, $size) = @_;
    my $buffer = "";
    my $partial;
    while ($size > 0) {
        my $bytes = sysread($sock, $partial, $size);
        next if (!defined($bytes) && $! == EAGAIN);
        return undef if (!defined($bytes) || $bytes < 0);
        last if ($bytes == 0);
        $buffer .= $partial;
        $size -= $bytes;
    }
    return $buffer;
}

sub bytestring($) {
    my $buffer = shift;
    return join(" ", unpack("(B8)*", $buffer));
}

sub varint($) {
    my $value = shift;
    my @bytes = ();

    while ($value > 0x7F) {
        push(@bytes, $value & 0x7F | 0x80);
        $value >>= 7;
    }
    push(@bytes, $value & 0x7F);
    return pack('C*', @bytes);
}

sub proto2bytes($) {
    my $proto = shift;
    my $payload = $proto->encode();
    my $size = length($payload);
    my $sizeobj = varint($size);
    printf "ENCODED SIZE: (%d) %s\n", length($payload), bytestring($sizeobj);
    printf "ENCODED OBJ : %s\n", bytestring($payload);
    printf "FRAME       : %s\n", bytestring($sizeobj . $payload);
    return $sizeobj . $payload;
}

sub bytes2proto($) {
    my $frame = shift;
    my @bytes = unpack('C*', $frame);
    my $vi = {value => 0, complete => 0};
    while (!$vi ->{complete}) {
        $vi = consume_varint($vi, shift(@bytes));
    }
    my $payload = pack('C*', @bytes);
    printf "FRAME       : %s\n", bytestring($frame);
    printf "DECODED SIZE: %d\n", $vi->{value};
    printf "PAYLOAD     : %s\n", bytestring($payload);
    my $proto = Org::Midonet::Cluster::Models::UUID->decode($payload);
    return $proto;
}

sub consume_varint($$) {
    my $varint = shift;
    my $byte = shift;
    $varint = {value => 0, complete => 0} if (!defined($varint));
    $varint->{value} = ($varint->{value} <<= 7) | ($byte & 0x7F);
    $varint->{complete} = ($byte & 0x80) == 0;
    return $varint;
}

if ($SERVER =~ /^ws:\/\/([^:\/]+)(?::(\d+))?/) {
    my $h = $1; $h = $DEFAULT_HOST if (!defined $h);
    my $p = $2; $p = $DEFAULT_WS_PORT if (!defined $p);
    print "websocket connection to ${h}:${p}\n";

    my $sock;
    socket($sock, PF_INET, SOCK_STREAM, getprotobyname("tcp"))
        || die "cannot create socket: $!\n";
    connect($sock, sockaddr_in($p, inet_aton($h)))
        || die "cannot connect: $!\n";
    print "socket ready\n";

    #my $flags = fcntl($sock, F_GETFL, 0);
    #fcntl($sock, F_SETFL, $flags | O_NONBLOCK);

    my $connected = 0;
    my $received = 0;
    my $client = Protocol::WebSocket::Client->new(url => $SERVER);
    $client->on(error => sub {
        my ($cl, $err) = @_;
        print "error: $err\n";
        exit(-1);
    });
    $client->on(write => sub {
        my ($cl, $data) = @_;
        socket_write($sock, $data);
        print "sent data: $data\n";
    });
    $client->on(read => sub {
        my ($cl, $frame) = @_;
        print "received data: $frame\n";

        my $uuid = bytes2proto($frame);
        printf "uuid msb: %d\n", $uuid->{msb};
        printf "uuid lsb: %d\n", $uuid->{lsb};

        $received = 1;
    });
    $client->on(connect => sub {
        my ($cl) = @_;
        print "websocket connection ready\n";
        $connected = 1;

        # Build frame
        my $frame = Protocol::WebSocket::Frame->new(
            version => $cl->{version},
            masked => 1,
            type => "binary",
            buffer => proto2bytes($proto_uuid),
        );
        $cl->write($frame);

    });

    $client->connect();
    print("connecting...\n");
    while(!$connected) { 
        my $byte = socket_read($sock, 1);
        last if (!defined($byte));
        $client->read($byte);
    }

    while (!$received) {
        my $byte = socket_read($sock, 1);
        last if (!defined($byte));
        $client->read($byte);
    }

    $client->disconnect();
    print "no more data\n";
    close($sock);

} else {
    my ($h, $p) = split(/:/, $SERVER, 2);
    $h = $DEFAULT_HOST if (!defined $h);
    $p = $DEFAULT_PORT if (!defined $p);
    print "plain connection to ${h}:${p}\n";

    my $sock;
    socket($sock, PF_INET, SOCK_STREAM, getprotobyname("tcp"))
        || die "cannot create socket: $!\n";
    connect($sock, sockaddr_in($p, inet_aton($h)))
        || die "cannot connect: $!\n";
    print "socket ready\n";

    my $flags = fcntl($sock, F_GETFL, 0);
    fcntl($sock, F_SETFL, $flags | O_NONBLOCK);

    my $data = proto2bytes($proto_uuid);
    socket_write($sock, $data);
    print "data sent\n";

    my $buffer = socket_read($sock, length($data));
    my $uuid = bytes2proto($buffer);
    printf "uuid msb: %d\n", $uuid->{msb};
    printf "uuid lsb: %d\n", $uuid->{lsb};

    print "no more data\n";
    close($sock);
}


