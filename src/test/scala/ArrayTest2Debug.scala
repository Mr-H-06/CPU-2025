import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

class ArrayTest2Debug extends AnyFlatSpec with ChiselScalatestTester {
  "Core" should "run array_test2.data with trace" in {
    test(new Core(initFile = "src/test/resources/array_test2.data", memSize = 131072, memDelay = 4)) { c =>
      c.clock.setTimeout(0)
      var cycles = 0
      var halted = false
      var lastPc: BigInt = -1
      var stagnant = 0
      val maxCycles = 5000
      val maxStagnant = 200

      while (cycles < maxCycles && !halted) {
        val pc = c.io.debug_pc.peek().litValue
        val haltedNow = c.io.halted.peek().litToBoolean
        val regs = c.io.debug_regs.map(_.peek().litValue)
        val robHeadPc = c.io.debug_rob_head_pc.peek().litValue
        val robHeadValid = c.io.debug_rob_head_valid.peek().litToBoolean
        val robHeadReady = c.io.debug_rob_head_ready.peek().litToBoolean
        val memReady = c.io.debug_mem_ready.peek().litToBoolean

        if (pc == lastPc) stagnant += 1 else stagnant = 0
        lastPc = pc

        val pcHex = f"0x${pc.toLong}%08x"
        // Focus on a small set of registers to keep logs readable during array_test2
        val regSlice = Seq(1, 2, 5, 8, 9, 10, 18).map(i => f"x$i=${regs(i)}%08x").mkString(" ")
        println(f"[cycle $cycles%5d] pc=$pcHex head_pc=0x${robHeadPc.toLong}%08x head_v=$robHeadValid head_rdy=$robHeadReady mem_rdy=$memReady $regSlice")

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
