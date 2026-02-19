import org.scalatest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import chisel3._
import chisel3.util._
import chiseltest._
import utils._

class ClearQueueTest extends AnyFlatSpec with ChiselScalatestTester {
  "ClearQueue" should "initialize correctly" in {
    test(new ClearQueue(UInt(32.W), 3)) { c =>
      c.io.enq.ready.expect(true.B)
      c.io.deq.valid.expect(false.B)
    }
  }

  "ClearQueue" should "work" in {
    test(new ClearQueue(UInt(32.W), 3)) { c =>
      c.io.enq.valid.poke(true.B)
      c.io.enq.bits.poke(1413.U)
      c.io.deq.ready.poke(false.B)
      c.io.clear.poke(false.B)
      c.clock.step(1)
      c.io.enq.valid.poke(true.B)
      c.io.enq.bits.poke(435.U)
      c.io.deq.ready.poke(true.B)
      c.io.clear.poke(false.B)
      c.io.deq.valid.expect(false.B)
      c.clock.step(1)
      c.io.deq.valid.expect(true.B)
      c.io.deq.bits.expect(1413.U)
    }
  }

  "ClearQueue" should "clear the queue" in {
    test(new ClearQueue(UInt(32.W), 3)) { c =>
      // Enqueue some data
      c.io.enq.valid.poke(true.B)
      c.io.enq.bits.poke(123.U)
      c.io.deq.ready.poke(false.B)
      c.io.clear.poke(false.B)
      c.clock.step(1)
      c.io.enq.valid.poke(false.B)
      c.io.deq.ready.poke(false.B)
      c.io.clear.poke(true.B)
      c.clock.step(1)
      c.io.enq.ready.expect(true.B)
      c.io.deq.valid.expect(false.B)
    }
  }

  "ClearQueue" should "handle enqueue when full" in {
    test(new ClearQueue(UInt(32.W), 1)) { c =>
      // Enqueue to fill
      c.io.enq.valid.poke(true.B)
      c.io.enq.bits.poke(456.U)
      c.io.deq.ready.poke(false.B)
      c.io.clear.poke(false.B)
      c.clock.step(1)
      c.io.enq.ready.expect(false.B) // Should be full
      // Try to enqueue again
      c.io.enq.valid.poke(true.B)
      c.io.enq.bits.poke(789.U)
      c.clock.step(1)
      c.io.enq.ready.expect(false.B)
    }
  }

  "ClearQueue" should "handle dequeue when empty" in {
    test(new ClearQueue(UInt(32.W), 3)) { c =>
      c.io.deq.ready.poke(true.B)
      c.io.deq.valid.expect(false.B)
      c.clock.step(1)
      c.io.deq.valid.expect(false.B)
    }
  }

  "ClearQueue" should "handle simultaneous enqueue and dequeue" in {
    test(new ClearQueue(UInt(32.W), 3)) { c =>
      // Enqueue first
      c.io.enq.valid.poke(true.B)
      c.io.enq.bits.poke(111.U)
      c.io.deq.ready.poke(false.B)
      c.io.clear.poke(false.B)
      c.clock.step(1)
      // Now enq and deq simultaneously
      c.io.enq.valid.poke(true.B)
      c.io.enq.bits.poke(222.U)
      c.io.deq.ready.poke(true.B)
      c.io.clear.poke(false.B)
      c.clock.step(1)
      c.io.deq.valid.expect(true.B)
      c.io.deq.bits.expect(111.U)
      c.clock.step(1)
      c.io.deq.valid.expect(true.B)
      c.io.deq.bits.expect(222.U)
    }
  }

  "ClearQueue" should "respect two-cycle delay" in {
    test(new ClearQueue(UInt(32.W), 3)) { c =>
      c.io.enq.valid.poke(true.B)
      c.io.enq.bits.poke(999.U)
      c.io.deq.ready.poke(true.B)
      c.io.clear.poke(false.B)
      c.clock.step(1)
      c.io.deq.valid.expect(false.B) // Not yet
      c.clock.step(1)
      c.io.deq.valid.expect(true.B)
      c.io.deq.bits.expect(999.U)
    }
  }

  "ClearQueue" should "work with different entry sizes" in {
    test(new ClearQueue(UInt(8.W), 2)) { c =>
      c.io.enq.valid.poke(true.B)
      c.io.enq.bits.poke(42.U)
      c.io.deq.ready.poke(false.B)
      c.io.clear.poke(false.B)
      c.clock.step(1)
      c.io.enq.valid.poke(false.B)
      c.io.deq.ready.poke(true.B)
      c.io.clear.poke(false.B)
      c.clock.step(1)
      c.io.deq.valid.expect(true.B)
      c.io.deq.bits.expect(42.U)
    }
  }
}