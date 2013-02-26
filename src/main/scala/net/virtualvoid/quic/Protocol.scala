package net.virtualvoid.quic

object Protocol {
  object FrameTypes {
    val PADDING_FRAME = 0
    val STREAM_FRAME = 1
    val ACK_FRAME = 2
    val CONGESTION_FEEDBACK_FRAME = 3
    val RST_STREAM_FRAME = 4
    val CONNECTION_CLOSE_FRAME = 5
  }

  object PublicFlags {
    val PACKET_PUBLIC_FLAGS_NONE    = 0      // = 0
    val PACKET_PUBLIC_FLAGS_VERSION = 1 << 0 // = 1
    val PACKET_PUBLIC_FLAGS_RST     = 1 << 1 // = 2
  }
  object PrivateFlags {
    val PACKET_PRIVATE_FLAGS_NONE        = 0       //  = 0
    val PACKET_PRIVATE_FLAGS_FEC         = 1 << 0  //  = 1 Payload is FEC as opposed to frames.
    val PACKET_PRIVATE_FLAGS_ENTROPY     = 1 << 1  //  = 2
    val PACKET_PRIVATE_FLAGS_FEC_ENTROPY = 1 << 2  //  = 4
  }
}
