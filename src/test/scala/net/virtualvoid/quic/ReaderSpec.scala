package net.virtualvoid.quic

import org.specs2.mutable.Specification
import akka.util.ByteString

class ReaderSpec extends Specification {
  "Reader" should {
    "round-trip with encoder" in {
      "uint8" in {
        check(244, Encoder.uint8, _.uint8())
        check(123, Encoder.uint8, _.uint8())
      }
      "uint16" in {
        check(12376, Encoder.uint16, _.uint16())
      }
      "uint32" in {
        check(123981723, Encoder.uint32, _.uint32())
      }
      "uint64" in {
        check(123981723L, Encoder.uint64, _.uint64())
      }

      "uint48" in {
        check(123981723L, Encoder.uint48, _.uint48())
      }

      "be_uint32" in {
        check(123981723, Encoder.be_uint32, _.be_uint32())
      }
    }
  }

  def check[T](value: T, encoder: T => ByteString, decoder: Reader => T) {
    decoder(Reader(encoder(value))) must be_==(value)
  }
}
