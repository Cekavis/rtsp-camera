# Third-party streaming components

RTSP Camera uses these pinned Apache License 2.0 projects. Their unmodified license texts are included beside this file.

| Project | Version and revision | Use |
| --- | --- | --- |
| [RootEncoder](https://github.com/pedroSG94/RootEncoder) | 2.8.1, `cca548ab7942ab50d34dede272d5da90d46c6b67` | Dependency modules `encoder` and `rtsp`; RTSP Camera directly reuses `H264Packet`, `H265Packet`, `AacPacket`, `BasePacket`, and `MediaFrame` for video and audio RTP packetization. |
| [RTSP-Server](https://github.com/pedroSG94/RTSP-Server) | 1.4.3, `3b54a59bf21e6d0e337c67516fdd97bb7eed23f7` | Protocol/control-layer reference. This project does not include its binary or present its existing authentication or lifecycle behavior as unchanged. |

Source references:

- https://github.com/pedroSG94/RootEncoder/tree/2.8.1/rtsp/src/main/java/com/pedro/rtsp/rtp/packets
- https://github.com/pedroSG94/RootEncoder/blob/2.8.1/common/src/main/java/com/pedro/common/frame/MediaFrame.kt
- https://github.com/pedroSG94/RTSP-Server/tree/1.4.3/rtspserver/src/main/java/com/pedro/rtspserver/server

The application's `com.cekavis.rtspcamera.rtsp` control layer is implemented locally. Relative to the reference, it separates the listener from capture, uses connection-bound Digest authentication with nonce-count checks, limits sessions and queues, serializes complete TCP messages, demultiplexes interleaved RTCP, and releases media demand on pause/disconnect. It exposes RTSP 1.0 playback of one video track and optional AAC-LC audio at `/live` over unicast TCP or UDP. Audio/video tracks use independent transports and SSRCs with a shared capture timebase and RTCP CNAME. ANNOUNCE/RECORD publishing, TLS, multicast, and arbitrary parameter changes are not implemented.

RootEncoder API compatibility was checked against the actual 2.8.1 source, including `MediaFrame.Info(offset, size, timestamp, isKeyFrame, flags)` and `AacPacket(track).setAudioInfo(sampleRate)`; timestamps passed to its packetizers are microseconds. For fragmented AAC access units, the local transport corrects RootEncoder's AU-size header to contain the full access-unit size, as required by RFC 3640. Retain these attribution and license files when distributing source or binaries. Dependency updates require renewed protocol, decoder, and device verification.
