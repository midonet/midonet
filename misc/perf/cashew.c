#define _GNU_SOURCE
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
#include <time.h>

#include <hdr_histogram.h>

#define PKTGEN_MAGIC htonl(0xbe9be955)
#define UDP 17
#define MAX_SECONDS (60 * 60 * 24)
#define BUFFER_SIZE 65536
#define NUM_BUFFERS 128
#define LOWEST_LAT_US 10
#define HIGHEST_LAT_US (1000 * 500)

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

int process_msg(struct msghdr *msg, char *buffer, struct hdr_histogram *hist)
{
    struct timeval tx_timestamp;
    struct timeval rx_timestamp;
    int tx_rx = 0;

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
                tx_rx += 1;
                break;
            }
            default:
                break;
            }
        case IPPROTO_IP:
            if (pktgen_packet(buffer, &tx_timestamp)) {
                tx_rx += 1;
                break;
            } else {
                return 0;
            }
        default:
            break;
        }
    }

    if (tx_rx == 2) {
        uint64_t latency = ((rx_timestamp.tv_sec - tx_timestamp.tv_sec) / 1000000) +
                           (rx_timestamp.tv_usec - tx_timestamp.tv_usec);
        hdr_record_value(hist, latency);
        return 1;
    }
    return 0;
}

int rcv_packet(int sock_raw, char *buffers, struct hdr_histogram *hist)
{
    struct mmsghdr msgs[NUM_BUFFERS];
    struct iovec entries[NUM_BUFFERS];
    struct {
        struct cmsghdr cm;
        char control[512];
    } control[NUM_BUFFERS];
    struct timespec timeout;
    int valid_pkts = 0;
    int npkts = 0;

    memset(&timeout, 0, sizeof(timeout));
    memset(msgs, 0, sizeof(msgs));
    for (int i = 0; i < NUM_BUFFERS; ++i) {
        entries[i].iov_base = &buffers[i * BUFFER_SIZE];
        entries[i].iov_len = BUFFER_SIZE;
        msgs[i].msg_hdr.msg_iov = &entries[i];
        msgs[i].msg_hdr.msg_iovlen = 1;
        msgs[i].msg_hdr.msg_control = &control[i];
        msgs[i].msg_hdr.msg_controllen = sizeof(control[i]);
    }

    npkts = recvmmsg(sock_raw, msgs, NUM_BUFFERS, MSG_WAITFORONE, &timeout);
    if (npkts < 0) {
        perror("recvmmsg()");
        return 0;
    }

    for (int i = 0; i < npkts; ++i) {
        if (process_msg(&msgs[i].msg_hdr, &buffers[i * BUFFER_SIZE], hist))
            valid_pkts += 1;
    }
    return valid_pkts;
}

const char* USAGE =
"cashew [-p <packets>] [-t <filename>] [-l <filename>]\n"
"  packets: <number> Number of packets to capture (default infinite).\n"
"  filename: <string> Name of the file in which to log throughput in gnuplot \
                      format and/or latency in HDR format (default stdout).\n";

int handle_opts(
    int argc,
    char **argv,
    uint64_t *expected_packets,
    char **pps_filename,
    char **lat_filename)
{
    int c;

    *expected_packets = 0;
    *pps_filename = NULL;
    *lat_filename = NULL;
    while ((c = getopt(argc, argv, "p:t:l:")) != -1)
    {
        switch (c)
        {
        case 'h':
            return 0;
        case 'p':
            if (sscanf(optarg, "%"PRIu64, expected_packets) < 1)
                return 0;
            break;
        case 't':
            *pps_filename = optarg;
            break;
        case 'l':
            *lat_filename = optarg;
            break;
        default:
            return 0;
        }
    }

    return optind >= argc ? 1 : 0;
}

struct hdr_histogram *hist;
FILE *pps_output;
FILE *lat_output;
int sock_raw;
unsigned int *pps;
time_t start = -1;
uint64_t received_packets = 0;

int intcmp(const void *a, const void *b)
{
    const unsigned int *i = a, *j = b;
    return (*i < *j) ? -1 : (*i > *j);
}

void report()
{
    unsigned int current_sec = start > 0 ? time(NULL) - start + 1 : 0;
    int first_zero = -1;
    unsigned int valid_cnt = 0;
    unsigned int valid_pps[MAX_SECONDS];

    fprintf(pps_output, "# seconds \t k packets\n");
    if (current_sec > MAX_SECONDS)
        current_sec = MAX_SECONDS;
    for (int i = 0; i < current_sec; ++i) {
        // Omit trailing zeros
        if (pps[i] == 0) {
            if (first_zero < 0)
                first_zero = i;
            continue;
        }
        if (first_zero >= 0) {
            for (int j = first_zero; j < i; ++j) {
                fprintf(pps_output, "%d \t %d\n", j, 0);
                valid_pps[valid_cnt++] = 0;
            }
            first_zero = -1;
        }
        fprintf(pps_output, "%d \t %d\n", i, pps[i]);
        valid_pps[valid_cnt++] = pps[i];
    }
    qsort(valid_pps, valid_cnt, sizeof(unsigned int), intcmp);

    hdr_percentiles_print(hist, lat_output, 5, 1.0, CLASSIC);

    printf("TOTAL_PACKETS=%"PRIu64"\n", received_packets);
    if (received_packets > 0) {
        printf("MEDIAN_PPS=%u\n", valid_pps[valid_cnt / 2]);
        printf("MAX_PPS=%u\n", valid_pps[valid_cnt - 1]);
        printf("LAT_50=%"PRIu64"\n", hdr_value_at_percentile(hist, 50.0));
        printf("LAT_75=%"PRIu64"\n", hdr_value_at_percentile(hist, 75.0));
        printf("LAT_90=%"PRIu64"\n", hdr_value_at_percentile(hist, 90.0));
        printf("LAT_99=%"PRIu64"\n", hdr_value_at_percentile(hist, 99.0));
        printf("LAT_99_9=%"PRIu64"\n", hdr_value_at_percentile(hist, 99.9));
    }

    close(sock_raw);
}

void on_signal(int signum) {
    report();
    exit(1);
}

int main(int argc, char **argv)
{
    struct sigaction action;
    char *pps_filename;
    char *lat_filename;
    uint64_t expected_packets;
    socklen_t len = 4;
    int enabled = 1;
    int val = 0;
    unsigned char *buffers = (unsigned char *)malloc(BUFFER_SIZE * NUM_BUFFERS);

    pps = (unsigned int *)malloc(sizeof(unsigned int) * MAX_SECONDS);

    memset(pps, 0, sizeof(unsigned int) * MAX_SECONDS);

    if (!handle_opts(argc, argv, &expected_packets, &pps_filename, &lat_filename))
    {
        printf("%s", USAGE);
        return 0;
    }

    if (pps_filename)
    {
        pps_output = fopen(pps_filename, "a+");
        if (!pps_output)
            ERROR("Failed to open/create file: %s, %s", pps_filename, strerror(errno));
    }
    else
    {
        pps_output = stdout;
    }

    if (lat_filename)
    {
        lat_output = fopen(lat_filename, "a+");
        if (!lat_output)
            ERROR("Failed to open/create file: %s, %s", lat_filename, strerror(errno));
    }
    else
    {
        lat_output = stdout;
    }

    if (hdr_init(LOWEST_LAT_US, HIGHEST_LAT_US, 4, &hist) != 0)
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
    action.sa_handler = on_signal;
    sigaction(SIGINT, &action, NULL);

    if (expected_packets == 0)
        expected_packets = ~UINT64_C(0);

    while (received_packets < expected_packets)
    {
        int npkts = rcv_packet(sock_raw, buffers, hist);
        if (npkts == 0)
            continue;
        if (start == -1)
            start = time(NULL);
        uint64_t second = time(NULL) - start;
        pps[second] += npkts;
        received_packets += npkts;
    }

    report();
    return 0;
}

