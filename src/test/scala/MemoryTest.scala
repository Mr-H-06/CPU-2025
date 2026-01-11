import org.scalatest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import chisel3._
import chisel3.util._
import chiseltest._
import utils._

class MemoryTest extends AnyFlatSpec with ChiselScalatestTester {
  "Memory" should "initialize correctly" in {
    test(new Memory("docs/memory-example.data", 1 << 14)) { c =>
      c.io.iread.address.poke(0x00001000.U)
      c.io.iread.valid.poke(true.B)
      c.clock.step(1)
      c.io.iout.valid.expect(true.B)
      c.io.iout.data.expect(0x00001737.U)
    }
  }
}