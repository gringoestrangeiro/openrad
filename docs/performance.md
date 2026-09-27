# Performance improvements

OpenRad now allows up to **24 outgoing peer handshakes at once**, compared with
four previously. A total limit of **32 handshakes** leaves capacity for incoming
offers when outgoing attempts are stalled. Established channels do not consume
handshake slots, and cancelled workers retain their slots until they exit.

Both the desktop application and CLI use these limits. Incoming offers have
priority, followed by peers selected for application traffic.

## Local measurements

| Operation | Before | After |
| --- | ---: | ---: |
| 100,000 idle socket checks | 37.97 ms | 21.66 ms |
| 200,000 Ethernet packet encodes | 12.55 ms | 5.26 ms |
| 10,000 TCP frames over localhost | 27.65 ms | 14.68 ms |

These measurements were collected on Linux x86-64 using an optimized release
build. Each result is the median of seven runs, alternating the order of the
original and optimized implementations. Socket checks used a zero timeout;
Ethernet frames and TCP payloads were 1,414 bytes. The TCP measurement includes
framing, transmission, and receipt over localhost, without encryption.

The measurements describe local processing and I/O overhead. They do not
measure public-network connection time or end-to-end VPN throughput. Actual
connection time also depends on peer responses, NAT, and relay availability.

## Implementation changes

- Linux TCP channels use `poll` instead of repeatedly changing socket flags and
  peeking for data. Long waits remain cancellable.
- TCP frame headers and bodies use vectored writes, including correct handling
  of partial and interrupted writes.
- Ethernet envelopes use one allocation.
- The desktop rebuilds membership views and forwarding eligibility only when
  membership changes, rather than every five milliseconds.
- Disabled reports skip JSON serialization.

Protocol message layouts, cryptography, authentication sequences, UDP formats,
candidate ordering within an attempt, and transport deadlines are unchanged.
Vectored TCP writes preserve the same framing and payload byte stream.
