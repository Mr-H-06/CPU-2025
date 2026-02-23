import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

class MemoryBasicopt1SmokeTest extends AnyFlatSpec with ChiselScalatestTester {
  "Memory" should "read first instruction of basicopt1" in {
    test(new Memory("src/test/resources/basicopt1.data", 1 << 14, 4)).withAnnotations(TestBackend.annos) { c =>
      c.io.iread.address.poke(0.U)
      c.io.iread.valid.poke(true.B)
      c.clock.step(1)
      c.io.iread.valid.poke(false.B)
      c.clock.step(1)
      c.io.iout.valid.expect(true.B)
      c.io.iout.data.expect("h00020137".U)
    }
  }
}
