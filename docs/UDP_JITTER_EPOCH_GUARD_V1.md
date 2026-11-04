# V1.3.10 — UDP jitter deadline and retired epoch guard

## Functional issue fixed

The RTP/UDP socket timeout was 100ms despite a nominal 55ms reorder
budget. If the final packet arrived immediately before a gap and then the
stream went quiet, the receiver could sleep for up to its socket timeout
before ticking the reorder queue. This change reduces the RTP receive timeout
to 25ms and drains all already-expired reorder gaps in one tick. Kernel
scheduling and camera output still determine actual wall-clock latency;
no glass-to-glass reduction is claimed.

In V1.3.9, every SSRC/payload-type change cleared the pending UDP window.
A late UDP packet from the prior source could then flip the active source
back, resulting in repeated resets. Now up to four retired SSRC/PT identities
are held for a bounded 256 received-packet arrivals. Matching late packets
are discarded instead of advancing the source epoch. The old source can be
accepted again after the bounded guard expires. The downstream H264 parser
remains responsible for waiting for a complete IDR on a genuine switch.

## Limits and tests

RtpJitterDeadlineEpochTest validates a quiet socket's multi-gap expirations,
SSRC/payload-type stream changes, late previous-source packet rejection,
expiry of retired identities and RTP 16-bit sequence rollover. An Android
socket's exact wake-up latency cannot be established by JVM tests.
The app does not transmit, record, or manufacture camera packets in this fix.
The change does not require a paid service, proprietary model or new permission.

Hardware testing is deferred until the user-approved predevice gate. This
does not establish real camera stability, a one-second preview, geometry
or lane/lead distance ground truth. Acceptance: 7/20=35% PASS CI, with
mandatory #14/#15/#16/#17/#20 not PASS.
