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
      val maxCycles = 100 // allow program to halt while collecting debug traces
      val maxStagnant = 50 // fail fast on suspected dead loop

      while (cycles < maxCycles && !halted) {
        val pc = c.io.debug_pc.peek().litValue
        val haltedNow = c.io.halted.peek().litToBoolean
        val cdb = c.io.debug_cdb_rob
        if (pc == lastPc) stagnant += 1 else stagnant = 0
        lastPc = pc

        val pcHex = f"0x${pc.toLong}%08x"
        // Print PC plus CDB activity to illustrate execute/complete order vs fetch order
        val cdbMsg = if (cdb.valid.peek().litToBoolean) {
          val idx = cdb.bits.index.peek().litValue.toInt
          val value = cdb.bits.value.peek().litValue
          f" cdb(tag=$idx,value=0x$value%08x)"
        } else ""

        println(f"[cycle $cycles%4d] pc=$pcHex halted=$haltedNow stagnant=$stagnant$cdbMsg")

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
