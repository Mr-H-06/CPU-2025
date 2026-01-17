import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import utils._

class HexInitSmokeTest extends AnyFlatSpec with ChiselScalatestTester {
  "Core" should "load prog.hex, avoid premature halt on 0x0ff00513, and produce 123" in {
    test(new Core(initFile = "src/test/resources/prog.hex", memSize = 8192, memDelay = 4)) { c =>
      c.clock.setTimeout(0)
      c.reset.poke(true.B)
      c.clock.step()
      c.reset.poke(false.B)

      var cycles = 0
      while (cycles < 1500) {
        // 确保不会在 0x0ff00513 误触发 halt
        if (c.io.halted.peek().litToBoolean) {
          fail(s"halted unexpectedly at cycle $cycles")
        }
        c.clock.step()
        cycles += 1
      }

      c.io.debug_reg_a0.expect(123.U)
    }
  }
}