import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

class CoreTest extends AnyFlatSpec with ChiselScalatestTester {
  "Core" should "halt on sb x0,-1(x0)" in {
    test(new Core(initFile = "src/test/resources/halt.data", memSize = 131072, memDelay = 4)) { c =>
      // run for limited cycles until halted
      var cycles = 0
      while (cycles < 20 && !c.io.halted.peek().litToBoolean) {
        c.clock.step()
        cycles += 1
      }
      assert(c.io.halted.peek().litToBoolean, s"Core did not halt within $cycles cycles")
    }
  }
}
