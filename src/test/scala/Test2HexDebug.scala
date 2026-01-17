import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

class Test2HexDebug extends AnyFlatSpec with ChiselScalatestTester {
  "Core" should "run test2.hex and report progress" in {
    test(new Core(initFile = "src/test/resources/test2.hex", memSize = 8192, memDelay = 4)) { c =>
      c.clock.setTimeout(0)
      var cycles = 0
      var halted = false
      var lastPc: BigInt = -1
      var stagnant = 0
      val maxCycles = 2000 // temporarily raised for debug
      val maxStagnant = 50 // fail fast on suspected dead loop

      while (cycles < maxCycles && !halted) {
        val pc = c.io.debug_pc.peek().litValue
        val haltedNow = c.io.halted.peek().litToBoolean
        if (pc == lastPc) stagnant += 1 else stagnant = 0
        lastPc = pc

        val pcHex = f"0x${pc.toLong}%08x"
        println(f"[cycle $cycles%4d] pc=$pcHex halted=$haltedNow stagnant=$stagnant")

        if (stagnant >= maxStagnant) {
          fail(s"PC stuck at $pcHex for $stagnant cycles (possible dead loop)")
        }

        c.clock.step()
        cycles += 1
        halted = haltedNow
      }

      val reason = if (halted) "halted" else "timeout"
      println(s"Finished after $cycles cycles, halted=$halted, reason=$reason")

      val lastPcHex = if (lastPc >= 0) f"0x${lastPc.toLong}%08x" else lastPc.toString
      assert(halted, s"Core did not halt within $maxCycles cycles; last PC=$lastPcHex")
    }
  }
}
