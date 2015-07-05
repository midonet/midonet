#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <stdarg.h>
#include <errno.h>
#include <string.h>
#include <inttypes.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <arpa/inet.h>
#include <netinet/if_ether.h>
#include <netinet/ip.h>
#include <netinet/udp.h>
#include <signal.h>
#include <getopt.h>
#include <unistd.h>

#include <hdr_histogram.h>

#define PKTGEN_MAGIC htonl(0xbe9be955)
#define UDP 17
#define BUFFER_SIZE 65536
#define LOWEST_LAT_US 100
#define HIGHEST_LAT_US 1000000

#define S1(x) #x
#define S2(x) S1(x)
#define ERROR(msg, ...)                                 \
    do {                                                \
        print_error("%s :" S2(__LINE__) ": " msg        \
                    "\n", __func__, ##__VA_ARGS__);     \
        return 1;                                       \
    } while (0)

struct pktgenhdr {
    __be32 pgh_magic;
    __be32 seq_num;
    __be32 tv_sec;
    __be32 tv_usec;
};

void print_error(const char *fmt, ...)
{
    va_list ap;
    va_start(ap, fmt);
    vfprintf(stderr, fmt, ap);
    va_end(ap);
}

int pktgen_packet(char *buffer, struct timeval *timestamp)
{
    struct iphdr *iph = (struct iphdr*)(buffer + sizeof(struct ethhdr));
    if (iph->protocol == UDP)
    {
        unsigned short iphdrlen = iph->ihl * 4;
        struct udphdr *udph = (struct udphdr*)(buffer +
                sizeof(struct ethhdr) + iphdrlen);
        struct pktgenhdr *pkt = (struct pktgenhdr*)(buffer +
                sizeof(struct ethhdr) + iphdrlen + sizeof(struct udphdr));
        if (udph->len >= htons(sizeof(struct pktgenhdr)) &&
            pkt->pgh_magic == PKTGEN_MAGIC)
        {
            timestamp->tv_sec = htonl(pkt->tv_sec);
            timestamp->tv_usec = htonl(pkt->tv_usec);
            return 1;
        }
    }
    return 0;
}

int process_msg(struct msghdr *msg, char *buffer, uint64_t *latency)
{
    struct timeval tx_timestamp;
    struct timeval rx_timestamp;

    for (struct cmsghdr *cmsg = CMSG_FIRSTHDR(msg);
         cmsg;
         cmsg = CMSG_NXTHDR(msg, cmsg))
    {
        switch (cmsg->cmsg_level)
        {
        case SOL_SOCKET:
            switch (cmsg->cmsg_type)
            {
            case SO_TIMESTAMPNS:
            {
                struct timespec *stamp = (struct timespec *)CMSG_DATA(cmsg);
                rx_timestamp.tv_sec = stamp->tv_sec;
                rx_timestamp.tv_usec = stamp->tv_nsec / 1000;
                break;
            }
            default:
                break;
            }
        case IPPROTO_IP:
            if (pktgen_packet(buffer, &tx_timestamp))
                break;
            else
                return 0;
        default:
            break;
        }
    }

    *latency = (rx_timestamp.tv_sec - tx_timestamp.tv_sec) * 1000000;
    *latency += rx_timestamp.tv_usec - tx_timestamp.tv_usec;
    return 1;
}

int rcv_packet(int sock_raw, char *buffer, uint64_t *latency)
{
    struct msghdr msg;
    struct iovec entry;
    struct {
        struct cmsghdr cm;
        char control[512];
    } control;
    int res;

    memset(&msg, 0, sizeof(msg));
    msg.msg_iov = &entry;
    msg.msg_iovlen = 1;
    entry.iov_base = buffer;
    entry.iov_len = BUFFER_SIZE;
    msg.msg_control = &control;
    msg.msg_controllen = sizeof(control);

    res = recvmsg(sock_raw, &msg, 0);
    return res > 0 ? process_msg(&msg, buffer, latency) : 0;
}

const char* USAGE =
"cashew [-p <packets>] [-f <filename>]\n"
"  packets: <number> Number of packets to capture (default infinite).\n"
"  filename: <string> Name of the file to log to (default stdout).\n";

int handle_opts(
    int argc,
    char **argv,
    uint64_t *expected_packets,
    char **filename)
{
    int c;

    *expected_packets = 0;
    *filename = NULL;
    while ((c = getopt(argc, argv, "p:f:")) != -1)
    {
        switch (c)
        {
        case 'h':
            return 0;
        case 'p':
            if (sscanf(optarg, "%"PRIu64, expected_packets) < 1)
                return 0;
            break;
        case 'f':
            *filename = optarg;
            break;
        default:
            return 0;
        }
    }

    return optind >= argc ? 1 : 0;
}

struct hdr_histogram *hist;
FILE *output;
int sock_raw;

void print_histogram(int signum)
{
   hdr_percentiles_print(hist, output, 5, 1000.0, CLASSIC);
   close(sock_raw);
   exit(1);
}

int main(int argc, char **argv)
{
    struct sigaction action;
    char *filename;
    uint64_t expected_packets;
    uint64_t received_packets = 0;
    socklen_t len = 4;
    int enabled = 1;
    int val = 0;
    unsigned char *buffer = (unsigned char *)malloc(BUFFER_SIZE);

    if (!handle_opts(argc, argv, &expected_packets, &filename))
    {
        printf("%s", USAGE);
        return 0;
    }

    if (filename)
    {
        output = fopen(filename, "a+");
        if (!output)
            ERROR("Failed to open/create file: %s, %s", filename, strerror(errno));
    }
    else
    {
        output = stdout;
    }

    if (hdr_init(LOWEST_LAT_US, HIGHEST_LAT_US, 3, &hist) != 0)
    {
        ERROR("Failed to init histogram");
        return -1;
    }

    sock_raw = socket(AF_PACKET, SOCK_RAW, htons(ETH_P_ALL));
    if (sock_raw < 0)
        ERROR("Socket error: %s", strerror(errno));

    if (setsockopt(sock_raw, SOL_SOCKET, SO_TIMESTAMPNS,
                   &enabled, sizeof(enabled)) < 0 ||
        getsockopt(sock_raw, SOL_SOCKET, SO_TIMESTAMPNS, &val, &len) < 0 ||
        val == 0)
    {
            ERROR("Failed to configure rx timestamps: %s", strerror(errno));
    }

    memset(&action, 0, sizeof(struct sigaction));
    action.sa_handler = print_histogram;
    sigaction(SIGINT, &action, NULL);

    setbuf(stdout, NULL);

    if (expected_packets == 0)
        expected_packets = ~UINT64_C(0);
    else
        printf("Expecting %" PRIu64 " packets\n", expected_packets);

    while (received_packets < expected_packets)
    {
        uint64_t latency;
        if (rcv_packet(sock_raw, buffer, &latency))
        {
            received_packets += 1;
            hdr_record_value(hist, latency);
            printf("Total packets received: %"PRIu64"\r", received_packets);
        }
    }

    hdr_percentiles_print(hist, output, 5, 1000.0, CLASSIC);
    close(sock_raw);
    return 0;
}

