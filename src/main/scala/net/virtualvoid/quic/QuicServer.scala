package net.virtualvoid.quic

import akka.actor.{ActorRef, Actor}
import akka.io._
import UdpFF._
import java.net.{DatagramSocket, InetSocketAddress}
import akka.util.ByteString
import java.nio.{ByteOrder, ByteBuffer}
import annotation.tailrec
import java.math.BigInteger
import net.virtualvoid.quic.Decoder.NullCrypter
import collection.SortedSet
import java.nio.channels.DatagramChannel

class QuicServer extends Actor {
  val endpoint = new InetSocketAddress("127.0.0.1", 8443)
   IO(UdpFF)(context.system) ! Bind(self, endpoint)

  def receive = unbound

  def unbound: Receive = {
    case Bound =>
      println("Now bound")
      context.become(bound(sender))
  }

  def bound(worker: ActorRef): Receive = {
    var leastUnacked = 0L
    var nextSeqId = 1L

    var largestObserved = 0L

    // invariants:
    // !receivedPackets.exists(_ <= receivedAllUpTo)
    var receivedAllUpTo = 0L
    var receivedPackets: SortedSet[Long] = SortedSet.empty[Long]

    def missingPackets =
      ((receivedAllUpTo + 1) until largestObserved).filterNot(receivedPackets)

    def ack() =
      AckFrame(SentInfo(leastUnacked, 0),
               ReceivedInfo(largestObserved, 0, missingPackets))

    def handleReceivedPacket(seq: Long): Unit = {
      largestObserved = largestObserved.max(seq)

      if (seq == receivedAllUpTo + 1) {
        receivedAllUpTo += 1
        if (receivedPackets(seq)) receivedPackets -= seq
      }
      else if (seq <= receivedAllUpTo) new IllegalStateException(s"Didn't expect package $seq, largest: $largestObserved, missing: $missingPackets")
      else receivedPackets += seq
    }

    def handleFrame(client: InetSocketAddress, sessionId: Long, ack: Option[AckFrame])(frame: Frame): Unit =
      frame match {
        case StreamFrame(1, _, _, data) =>
          val handshake = Decoder.readHandshakeMessage(data)
          handshake match {
            case CHLO =>
              val packet =
                DataPacket(sessionId, nextSeqId, 0, 0xff,
                  Seq(StreamFrame(1, false, 0, Encoder.shlo)) ++ ack.toSeq
                )
              nextSeqId += 1
              send(packet, client)
          }
        case StreamFrame(x, _, _, data) =>
          println(s"Got new stream $x")
          val req = Decoder.decodeHttpRequest(data)
          println(s"Http request $req")

          val packet = DataPacket(sessionId, nextSeqId, 0, 0xff,
            Seq(StreamFrame(x, true, 0, Encoder.encodeHttpResponse("200", "HTTP/1.1", Nil, ByteString("Hello world!")))) ++ ack.toSeq)
          nextSeqId += 1
          send(packet, client)
        case x@_ => println("Ignoring frame "+x)
      }

    def send(packet: DataPacket, client: InetSocketAddress): Unit = {
      /*val ch = DatagramChannel.open()
      ch.bind(endpoint)

      val encoded = Encoder.encodePacket(packet, Decoder.NullCrypter)
      val res = ch.send(encoded.asByteBuffer, client)
      println(s"Send result $res")
      ch.close()*/
      sender ! Send(Encoder.encodePacket(packet, Decoder.NullCrypter), client)
    }

    {
      case Received(data, client) =>
        println(s"Got package with size ${data.length} from $client")
        val packet = Decoder.readPacket(data, Decoder.NullCrypter)
        packet match {
          case DataPacket(sessionId, seq, flags, fec, frames) =>
            handleReceivedPacket(seq)

            frames.foreach(handleFrame(client, sessionId, Some(ack())))
          case p@_ => println("Ignoring packet "+p)
        }

    }
  }


}

case class SentInfo(leastUnacked: Long, entropyHash: Int)
case class ReceivedInfo(largestObserved: Long, entropyHash: Int, missingPackets: Seq[Long])


import Protocol._
object Encoder {
  def roundTripCheck(packet: Packet, crypter: Crypter)(encoder: Packet => ByteString): ByteString = {
    val res = encoder(packet)
    println("Now reading result")
    val decoded = Decoder.readPacket(res, crypter)
    assert(packet == decoded, s"Wasn't equal $packet != $decoded")
    res
  }
  def encodePacket(packet :Packet, crypter: Crypter): ByteString = roundTripCheck(packet, crypter) {
    case DataPacket(sessionId, sequence, privateFlags, fecGroup, frames) =>
      val publicData =
        uint64(sessionId) ++
        uint8(0 /* public flag for normal data package */) ++
        uint48(sequence)

      publicData ++ crypter.encrypt(encodePrivatePart(privateFlags, fecGroup, frames), publicData)
  }
  def encodePrivatePart(privateFlags: Int, fecGroup: Int, frames: Seq[Frame]): ByteString =
    uint8(privateFlags) ++ uint8(fecGroup) ++ frames.map(encodeFrame).foldLeft(ByteString.empty)(_ ++ _)

  def encodeFrame(frame: Frame): ByteString = frame match {
    case StreamFrame(id, fin, offset, data) =>
      uint8(FrameTypes.STREAM_FRAME) ++
      uint32(id) ++
      uint8(if (fin) 1 else 0) ++
      uint64(offset) ++
      lengthAndBytes(data)
    case AckFrame(SentInfo(leastUnacked, sentEntropyHash), ReceivedInfo(largestObserved, receivedEntropyHash, missingPackets)) =>
      assert(missingPackets.length < 256)

      uint8(FrameTypes.ACK_FRAME) ++
      uint8(sentEntropyHash) ++
      uint48(leastUnacked) ++
      uint8(receivedEntropyHash) ++
      uint48(largestObserved) ++
      uint8(missingPackets.length) ++
      missingPackets.map(uint48).foldLeft(ByteString.empty)(_ ++ _)
  }

  def encodeHttpResponse(status: String, version: String, headers: Seq[(String, String)], data: ByteString): ByteString = {
    def string32(str: String) = {
      val encoded = ByteString(str, "ASCII")
      be_uint32(encoded.length) ++ encoded
    }
    def encodeHeader(key: String, value: String): ByteString =
      string32(key) ++ string32(value)

    val allHeaders = (":status", status) :: (":version", version) :: headers.toList

    be_uint32(allHeaders.length) ++
    allHeaders.map((encodeHeader _).tupled).foldLeft(ByteString.empty)(_ ++ _) ++
    data
  }

  def shlo: ByteString = ByteString("SHLO", "ASCII") ++ uint16(0)

  def bytes(bs: Int*): ByteString = ByteString(bs.map(_.toByte).toArray)
  def lbytes(bs: Long*): ByteString = ByteString(bs.map(_.toByte).toArray)
  def uint32(i: Int): ByteString =
    bytes(
      (i >>  0) & 0xff,
      (i >>  8) & 0xff,
      (i >> 16) & 0xff,
      (i >> 24) & 0xff)
  def uint16(i: Int): ByteString =
    bytes(
      (i >>  0) & 0xff,
      (i >>  8) & 0xff)

  def uint8(i: Int): ByteString = ByteString(Array(i.toByte))
  def uint64(i: Long): ByteString =
    lbytes(
      (i >>  0) & 0xff,
      (i >>  8) & 0xff,
      (i >> 16) & 0xff,
      (i >> 24) & 0xff,
      (i >> 32) & 0xff,
      (i >> 40) & 0xff,
      (i >> 48) & 0xff,
      (i >> 56) & 0xff)

  def uint48(i: Long): ByteString =
    lbytes(
      (i >>  0) & 0xff,
      (i >>  8) & 0xff,
      (i >> 16) & 0xff,
      (i >> 24) & 0xff,
      (i >> 32) & 0xff,
      (i >> 40) & 0xff)

  def be_uint32(i: Int): ByteString =
    bytes(
      (i >> 24) & 0xff,
      (i >> 16) & 0xff,
      (i >>  8) & 0xff,
      (i >>  0) & 0xff)

  def lengthAndBytes(data: ByteString): ByteString = {
    require(data.length < 0x10000)
    uint16(data.length) ++ data
  }
}

trait Crypter {
  def decrypt(data: ByteString, associated: ByteString): ByteString
  def encrypt(data: ByteString, associated: ByteString): ByteString
}


sealed trait Packet {
  def sessionId: Long
}
case class DataPacket(sessionId: Long, sequence: Long, privateFlags: Int, fecGroup: Int, frames: Seq[Frame]) extends Packet
case class UnknownPacket(sessionId: Long, flags: Int) extends Packet

sealed trait Frame
case class StreamFrame(id: Int, fin: Boolean, offset: Long, data: ByteString) extends Frame
case class AckFrame(sentInfo: SentInfo, receivedInfo: ReceivedInfo) extends Frame
case class RstFrame(streamId: Int, errorCode: Int, details: ByteString) extends Frame
case class ConnectionCloseFrame(errorCode: Int, details: ByteString, lastAck: AckFrame) extends Frame
case class UnknownFrame(tpe: Int) extends Frame

sealed trait HandshakeMessage {
  def tag: String
}
case object CHLO extends HandshakeMessage {
  def tag: String = "CHLO"
}

trait Reader {
  def lengthAndBytes(): ByteString
  def bytes(num: Int): ByteString
  def uint64(): Long
  def uint32(): Int
  def be_uint32(): Int
  def uint16(): Int
  def uint8(): Int
  def uint48(): Long
  def remainingBytes(): ByteString
}
object Reader {
  def apply(_bytes: ByteString): Reader = new Reader {
    val bb = _bytes.asByteBuffer
    bb.order(ByteOrder.LITTLE_ENDIAN)

    def lengthAndBytes(): ByteString = bytes(uint16())
    def bytes(length: Int): ByteString = {
      val buf = new Array[Byte](length)
      bb.get(buf)
      ByteString(buf)
    }

    def remainingBytes(): ByteString = bytes(bb.remaining())
    def uint64(): Long = bb.getLong
    def uint32(): Int = bb.getInt
    def uint16(): Int = bb.getShort //(bb.get() & (0xff << 0)) | (bb.get() & (0xff << 8))
    def uint8(): Int = bb.get() & 0xff

    def uint48(): Long =
      ((bb.get() & 0xff) <<  0) |
      ((bb.get() & 0xff) <<  8) |
      ((bb.get() & 0xff) << 16) |
      ((bb.get() & 0xff) << 24) |
      ((bb.get() & 0xff) << 32) |
      ((bb.get() & 0xff) << 40)

    def be_uint32(): Int =
      ((bb.get() & 0xff) << 24) |
      ((bb.get() & 0xff) << 16) |
      ((bb.get() & 0xff) <<  8) |
      ((bb.get() & 0xff) <<  0)
  }
}

object Decoder {

  object NullCrypter extends Crypter {

    def encrypt(bytes: ByteString, associated: ByteString): ByteString =
      HashUtils.fnv1a128(associated ++ bytes) ++ bytes

    def decrypt(bytes: ByteString, associated: ByteString): ByteString = {
      val hash = bytes.take(16)
      val res = bytes.drop(16)

      val checkHash = HashUtils.fnv1a128(associated ++ res)
      if (hash != checkHash) {
        println(s"hash doesn't match, expected $hash, got $checkHash")
      } //else println("Hash matched")
      res
    }
  }

  def readHandshakeMessage(data: ByteString): HandshakeMessage = {
    val cryptoReader = Reader(data)
    val tag = cryptoReader.bytes(4).decodeString("ASCII")
    val numEntries = cryptoReader.uint16()
    val keyTags = Seq.fill(numEntries)(cryptoReader.bytes(4)).map(_.decodeString("ASCII"))
    val lengths = Seq.fill(numEntries)(cryptoReader.uint16())
    val padding = if (numEntries % 2 == 1) cryptoReader.uint16() else 0
    assert(padding == 0)
    val values = lengths.map(cryptoReader.bytes)
    val entries = keyTags zip values
    println(s"Handshake: $tag")
    entries.foreach {
      case (key, value) => println(s"$key => $value")
    }
    tag match {
      case "CHLO" => CHLO
    }
  }
  def readPacket(bytes: ByteString, crypter: Crypter): Packet = {
    val reader = Reader(bytes)
    val guid = reader.uint64()
    val publicFlag = reader.uint8()

    if (publicFlag == 0) {
      val seqId = reader.uint48()

      val decrypted = crypter.decrypt(bytes.drop(15), bytes.take(15))
      val dataReader = Reader(decrypted)
      val privateFlag = dataReader.uint8()
      val fecGroup = dataReader.uint8()

      println(f"Public header: Guid: $guid%016X public_flags: $publicFlag seqid: $seqId " +
              f"privateFlag: $privateFlag fecGroup: $fecGroup")

      if ((privateFlag & PrivateFlags.PACKET_PRIVATE_FLAGS_FEC) != 0) {
        println("Got fec packet")
        UnknownPacket(guid, publicFlag)
      } else
        DataPacket(guid, seqId, privateFlag, fecGroup, readFrames(Seq.empty, dataReader.remainingBytes()))
    } else
      UnknownPacket(guid, publicFlag)
  }

  @tailrec def readFrames(results: Seq[Frame], rest: ByteString): Seq[Frame] =
    if (rest.isEmpty) results
    else {
      val (one, remaining) = readFrame(rest)
      if (remaining.nonEmpty) println(s"Got more data: ${rest.length}")
      readFrames(results :+ one, remaining)
    }

  def readFrame(decrypted: ByteString): (Frame, ByteString) = {
    //println(s"Got ${decrypted.length} decrypted bytes")
    val dataReader = Reader(decrypted)

    val frameType = dataReader.uint8()

    println(s"frameType: $frameType")

    def processFrame(frameType: Int): Frame =
      frameType match {
        case FrameTypes.STREAM_FRAME =>
          val id = dataReader.uint32()
          val fin = dataReader.uint8() == 1
          val offset = dataReader.uint64()
          val data = dataReader.lengthAndBytes()

          println(f"id: 0x$id%04x fin: $fin offset: $offset data length: ${data.length}")
          StreamFrame(id, fin, offset, data)
        case FrameTypes.ACK_FRAME =>
          val sentEntropyHash = dataReader.uint8()
          val leastUnacked = dataReader.uint48()
          val receivedEntropyHash = dataReader.uint8()
          val largestObserved = dataReader.uint48()

          val numMissing = dataReader.uint8()
          val missing = Seq.fill(numMissing)(dataReader.uint48())

          AckFrame(SentInfo(leastUnacked, sentEntropyHash),
                   ReceivedInfo(largestObserved, receivedEntropyHash, missing))

        case FrameTypes.CONGESTION_FEEDBACK_FRAME =>
          val tpe = dataReader.uint8()
          tpe match {
            case 0 => // kTCP
              val numOfLosts = dataReader.uint16()
              val receiveWindow = dataReader.uint16()

              println(s"Got tcp congestion feedback losts: $numOfLosts recvWindow: $receiveWindow")

            case _ =>
              println(s"[WARN] Can't handle congestion feedback of tpe $tpe")
            //case 1 => // kInterArrival
            //case 2 => // kFixRate
          }
          UnknownFrame(frameType)

        case FrameTypes.RST_STREAM_FRAME =>
          val streamId = dataReader.uint32()
          val errorCode = dataReader.uint32()
          val errorDetails = dataReader.lengthAndBytes()

          RstFrame(streamId, errorCode, errorDetails)

        case FrameTypes.CONNECTION_CLOSE_FRAME =>
          val errorCode = dataReader.uint32()
          val details = dataReader.lengthAndBytes()
          val ack = processFrame(FrameTypes.ACK_FRAME)

          ConnectionCloseFrame(errorCode, details, ack.asInstanceOf[AckFrame])
        case _ =>
          val rest = dataReader.remainingBytes()
          println(s"[WARN] Ignoring ${rest.length} remaining bytes")
          UnknownFrame(frameType)
      }

    (processFrame(frameType), dataReader.remainingBytes())
  }

  case class SimpleHttpRequest(headers: Seq[(String, String)])
  def decodeHttpRequest(bytes: ByteString): SimpleHttpRequest = {
    val reader = Reader(bytes)

    def string32() = reader.bytes(reader.be_uint32()).decodeString("ASCII")
    def kv(): (String, String) = (string32(), string32())

    val numEntries = reader.be_uint32()
    println(s"Found $numEntries http headers")
    val headers = Seq.fill(numEntries)(kv())
    SimpleHttpRequest(headers)
  }
}

object HashUtils {
  val FnvOffsetBasis = 0xcbf29ce484222325L
  val FnvPrime = 1099511628211L

  def fnv1a(bytes: ByteString): Long = {
    var hash = FnvOffsetBasis
    bytes.foreach { b =>
      hash ^= (b & 0xff)
      hash *= FnvPrime
    }
    hash
  }

  val Fnv128Prime = BigInt("309485009821345068724781371")
  // broken constant: the real constant has an additional 9 at the end
  val Fnv128OffsetBasis = BigInt("14406626329776981559649562966706236762")
  val Fnv128Mod = BigInt(2).pow(128)

  def fnv1a128(bytes: ByteString): ByteString = {
    var hash = Fnv128OffsetBasis
    bytes.foreach { b =>
      hash = hash ^ (b & 0xff)
      hash = (hash * Fnv128Prime) % Fnv128Mod
    }
    ByteString(hash.toByteArray.reverse.take(16))
  }
  def fnv1a128i(bytes: ByteString): BigInt = {
    var hash = Fnv128OffsetBasis
    bytes.foreach { b =>
      hash = hash ^ (b & 0xff)
      hash = (hash * Fnv128Prime) % Fnv128Mod
    }
    hash
  }

  def print(bs: ByteString, extra: String = "") = {
    val str = bs.map(_.formatted("%02x")).mkString(" ")
    println(f"$extra%10s | ${bs.length}%3d bytes | $str")
  }
}
