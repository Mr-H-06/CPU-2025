import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

class Test2HexDebug extends AnyFlatSpec with ChiselScalatestTester {
  "Core" should "run test2.hex and report progress" in {
    test(new Core(initFile = "src/test/resources/array_test1.data", memSize = 8192, memDelay = 4)) { c =>
      c.clock.setTimeout(0)
      var cycles = 0
      var halted = false
      var lastPc: BigInt = -1
      var stagnant = 0
      // array_test1 runs through division/mod routines and emits many control-flow redirects;
      // give it more headroom so we can observe a clean halt without tripping the debug guard.
      val maxCycles = 6000000
      val maxStagnant = 50

      while (cycles < maxCycles && !halted) {
        val pc = c.io.debug_pc.peek().litValue
        val haltedNow = c.io.halted.peek().litToBoolean
        val regs = c.io.debug_regs.map(_.peek().litValue)
        if (pc == lastPc) stagnant += 1 else stagnant = 0
        lastPc = pc

        val pcHex = f"0x${pc.toLong}%08x"
        val regMsg = {
          val nonZero = regs.zipWithIndex.collect { case (v, i) if v != 0 => f"x$i=$v%08x" }
          if (nonZero.nonEmpty) " regs=" + nonZero.mkString(" ") else " regs=all_zero"
        }

        // Only output cycle, PC, regs
        println(f"[cycle $cycles%5d] pc=$pcHex$regMsg")

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
