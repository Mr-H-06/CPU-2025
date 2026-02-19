import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

class SimpleCHexTest extends AnyFlatSpec with ChiselScalatestTester {
  "Core" should "run simple program and set a0=123 then halt" in {
    test(new Core(initFile = "src/test/resources/simple.hex", memSize = 131072, memDelay = 4)) { c =>
      c.clock.setTimeout(0)
      var cycles = 0
      while (cycles < 200 && !c.io.halted.peek().litToBoolean) {
        c.clock.step()
        cycles += 1
      }
      assert(c.io.halted.peek().litToBoolean, s"program did not halt in $cycles cycles")
    }
  }
}
