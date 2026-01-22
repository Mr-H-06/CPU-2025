import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

class TestHex extends AnyFlatSpec with ChiselScalatestTester {
  "Core" should "load test.hex without assertions" in {
    test(new Core(initFile = "src/test/resources/test.data", memSize = 131072, memDelay = 4)) { c =>
      c.clock.setTimeout(0)
      var cycles = 0
      // run a generous number of cycles; pass as long as no internal assertion fires
      while (cycles < 20000) {
        c.clock.step()
        cycles += 1
      }
    }
  }
}
