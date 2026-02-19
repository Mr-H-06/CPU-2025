import java.io.PrintWriter
import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

class BulgarianCommitTrace extends AnyFlatSpec with ChiselScalatestTester {
  "Core" should "dump bulgarian commit trace" in {
    val outPath = "tmp_dut_bulgarian_trace.txt"
    def intProp(name: String, default: Int): Int =
      sys.props.get(name).flatMap(v => scala.util.Try(v.toInt).toOption).getOrElse(default)
    val writer = new PrintWriter(outPath)
    try {
      test(new Core(initFile = "src/test/resources/bulgarian.data", memSize = 131072, memDelay = 4)) { c =>
        c.clock.setTimeout(0)
        var cycles = 0
        var commits = 0
        val maxCycles = intProp("cpu.traceMaxCycles", 250000)
        val maxCommits = intProp("cpu.traceMaxCommits", 120000)

        while (cycles < maxCycles && commits < maxCommits && !c.io.halted.peek().litToBoolean) {
          if (c.io.debug_commit_valid.peek().litToBoolean) {
            val pc = c.io.debug_commit_pc.peek().litValue.toLong
            val op = c.io.debug_commit_op.peek().litValue.toLong
            val rd = c.io.debug_commit_rd.peek().litValue.toLong
            val value = c.io.debug_commit_value.peek().litValue.toLong
            val isStore = c.io.debug_commit_is_store.peek().litToBoolean
            writer.println(f"CMT pc=0x$pc%08x op=0x$op%02x rd=$rd val=0x$value%08x store=$isStore")
            commits += 1
          }
          c.clock.step()
          cycles += 1
        }
        writer.println(s"END cycles=$cycles commits=$commits halted=${c.io.halted.peek().litToBoolean}")
      }
    } finally {
      writer.close()
    }
    println(s"wrote $outPath")
  }
}
