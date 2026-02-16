
import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

class Test2HexDebug extends AnyFlatSpec with ChiselScalatestTester {
  "Core" should "run test2.hex and report progress" in {
    test(new Core(initFile = "src/test/resources/test.data", memSize = 131072, memDelay = 4)) { c =>
      c.clock.setTimeout(0)
      var cycles = 0
      var halted = false
      var lastPc: BigInt = -1
      var stagnant = 0
      // array_test1 runs through division/mod routines and emits many control-flow redirects;
      // give it more headroom so we can observe a clean halt without tripping the debug guard.
      val maxCycles = 5000
      val maxStagnant = 50
      val printEveryCycle = false

      def opName(op: BigInt): String = op.toInt match {
        case 0x37 => "LUI"
        case 0x17 => "AUIPC"
        case 0x6F => "JAL"
        case 0x67 => "JALR"
        case 0x63 => "BRANCH"
        case 0x03 => "LOAD"
        case 0x23 => "STORE"
        case 0x13 => "OP-IMM"
        case 0x33 => "OP"
        case 0x73 => "SYSTEM"
        case 0x0F => "FENCE"
        case _ => f"OPC=0x${op.toInt & 0x7f}%02x"
      }

      while (cycles < maxCycles && !halted) {
        val pc = c.io.debug_pc.peek().litValue
        val haltedNow = c.io.halted.peek().litToBoolean
        val regs = c.io.debug_regs.map(_.peek().litValue)
        val x2 = regs(2)
        val x9 = regs(9)
        val commitValid = c.io.debug_commit_valid.peek().litToBoolean
        val commitPc = c.io.debug_commit_pc.peek().litValue
        val commitOp = c.io.debug_commit_op.peek().litValue
        val commitRd = c.io.debug_commit_rd.peek().litValue
        val commitIsStore = c.io.debug_commit_is_store.peek().litToBoolean
        val commitValue = c.io.debug_commit_value.peek().litValue
        val commitTag = c.io.debug_commit_tag.peek().litValue
        if (pc == lastPc) stagnant += 1 else stagnant = 0
        lastPc = pc

        val pcHex = f"0x${pc.toLong}%08x"
        val regMsg = {
          val nonZero = regs.zipWithIndex.collect { case (v, i) if v != 0 => f"x$i=$v%08x" }
          if (nonZero.nonEmpty) " regs=" + nonZero.mkString(" ") else " regs=all_zero"
        }

        // Only output cycle, PC, regs when enabled
        if (printEveryCycle) {
          println(f"[cycle $cycles%5d] pc=$pcHex$regMsg")
        }

        // Commit-style trace to compare with reference simulator
        if (commitValid) {
          val commitPcHex = f"0x${commitPc.toLong}%08x"
          val opStr = opName(commitOp)
          val extraCtx = if (commitRd == 9 || commitPc == 0x130) {
            f" x2=0x${x2.toLong}%08x x9=0x${x9.toLong}%08x"
          } else ""
          if (commitIsStore || commitOp == 0x23) {
            println(f"[commit $cycles%5d] pc=$commitPcHex op=$opStr tag=$commitTag STORE$extraCtx")
          } else if (commitRd != 0) {
            println(f"[commit $cycles%5d] pc=$commitPcHex op=$opStr rd=x${commitRd}%d val=0x${commitValue.toLong}%08x tag=$commitTag$extraCtx")
          } else {
            println(f"[commit $cycles%5d] pc=$commitPcHex op=$opStr tag=$commitTag$extraCtx")
          }
        }

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
