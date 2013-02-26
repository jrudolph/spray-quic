# Inofficial quic spec (as of svn revision 184347)

## Ideas
 - Encryption
 - FEC
 - Multiple

## Terminology

### Session
A session consists of
 - several streams
 - FEC settings
 - encryption setup
 - congestion avoidance setup
 - sequencing

It has a guid uniquely identifying it. (Why? I suspect to avoid be resilient against
ip/port changes when switching NATs or similar.)

There's usually only one session (one connection) per tuple of peers.

### Stream
A stream is a bidirectional pipe of ordered data similar to TCP streams. Each stream has an
id uniquely identifying it inside a session. Client initiated streams have odd ids, server
initiated even ones.

There's a stream with the well-known number 1 which is used to negotiate connection parameters like
encryption settings, congestion algorithms, etc.


## Packet layout
The package is divided into a plaintext and into a encrypted part.

The plaintext part contains
 - session guid
 - public flags
 - sequence number

The public data structure of the encrypted part is defined by the encrypter. The
encrypted data itself follows this structure:
 - private flags
 - fecGroup
 - Frame type
 - Frame data

## Encryption

The encryption are Authenticated Encryption with Associated Data (AEAD) algorithms. This
means that some data is only authenticated and the rest also encrypted. In the context of
quic associated data bytes are all of the first 15 public bytes of a packet. The plaintext
bytes are all bytes of the plaintext data.

### Null encryption
A 128bit hash followed by the actual data. The hash is calculated over the result of
appending the associated data bytes with the plaintext data bytes.

The hash algorithm used is currently a 128bit version of FNV-1a with these constant parameters:
offset: 14406626329776981559649562966706236762
prime: 309485009821345068724781371

Note: the offset constant currently differs from the one published here
http://www.isthe.com/chongo/tech/comp/fnv/ by a missing digit '9' at the end. See
https://codereview.chromium.org/12340054

### Handshake protocol

General format
 - uint32 message tag
 - uint16 numEntries
 - uint32[numEntries] keyTags
 - uint16[numEntries] valueLengths
 - (2 bytes padding if numEntries % 2 == 1)
 - numEntries * data of size valueLengths[i]

#### Client Hello
 - message tag = 'C', 'H', 'L', 'O'

### Code reading

#### Control flow for incoming packet data (client)

 - QuicClientSession::StartReading - calls reader with callback
 - QuicClientSession::OnReadComplete - sets up data structure
 - QuicConnection::ProcessUdpPacket
 - QuicFramer::ProcessPacket
 - QuicFramer::ProcessDataPacket - calls visitor->OnPacketHeader
 - (FEC handling)
 - QuicFramer::ProcessFrameData - switches on frame type
 - QuicFramer::ProcessStreamFrame - reads package, calls visitor->OnStreamFrame

 - QuicStreamSequencer::OnStreamFrame
 - QuicCryptoStream::ProcessData
 - QuicCryptoFramer::ProcessInput - calls visitor->OnHandshakeMessage
 - QuicCryptoClientStream::OnHandshakeMessage

For ack frame:

 - QuicFrame::ProcessAckFrame - reads infos and calls visitor->OnAckFrame
   - QuicFrame::ProcessSentInfo
   - QuicFrame::ProcessReceivedInfo
 - QuicConnection::OnAckFrame


