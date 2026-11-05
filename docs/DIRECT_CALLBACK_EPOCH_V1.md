# V1.3.14 – Direct RTSP callback epoch isolation

Activity generation does NOT change during an internal Direct RTSP
reconnection. Asynchronous YUV output from the old decoder or a late
onYuvDiscontinuity callback could previously enter the next player
session and contaminate the ONNX temporal pair / YUV evidence capture.

A per-player atomic DirectCallbackEpochGate now issues distinct leases on
startDirect, revokes the previous lease before recoverDirect closes the
old codec, and invalidates the current lease in stopPlayback. All YUV,
ONNX discontinuity, status/firstPicture, recovery and deferred YUV SAF
export callbacks require current ownership. The existing async native
MediaCodec release barrier remains unchanged.

A callback that was already executing when its lease was revoked is not
forcibly cancelled; this is a callback-admission guard, not a native
pipeline synchronization guarantee. Seven JVM tests use synthetic
callbacks; real A500S/YUV/ONNX validation and mandatory #14–17/#20 remain
NOT PASS. Engineering gate **7/20=35%**.
