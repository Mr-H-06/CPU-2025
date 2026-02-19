import java.io.PrintWriter
import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

class Test2HexDebug extends AnyFlatSpec with ChiselScalatestTester {
  "Core" should "run selected data as a fast x10 result window" in {
    val initFile = sys.props.getOrElse("cpu.initFile", "src/test/resources/test.data")
    test(new Core(initFile = initFile, memSize = 131072, memDelay = 4)).withAnnotations(TestBackend.annos) { c =>
      c.clock.setTimeout(0)

      def intProp(name: String, default: Int): Int =
        sys.props.get(name).flatMap(v => scala.util.Try(v.toInt).toOption).getOrElse(default)
      def longProp(name: String, default: Long): Long =
        sys.props.get(name).flatMap(v => scala.util.Try(v.toLong).toOption).getOrElse(default)

      val maxCycles = intProp("cpu.maxCycles", 2000000)
      val expectedA0 = longProp("cpu.expectA0", -1L)
      val traceOut = sys.props.getOrElse("cpu.x10TraceOut", "tmp_test2_x10_trace.txt")
      val traceStride = math.max(1, intProp("cpu.traceStride", 1))
      val commitOut = sys.props.getOrElse("cpu.a0CommitTraceOut", "tmp_test2_a0_commits.txt")
      val traceA0Commits = sys.props.get("cpu.traceA0Commits").exists(v => v == "1" || v.equalsIgnoreCase("true"))
      val enableProgress = sys.props.get("cpu.progress").exists(v => v == "1" || v.equalsIgnoreCase("true"))
      val progressEvery = intProp("cpu.progressEvery", 50000)

      var cycles = 0
      var halted = false
      var nextProgress = progressEvery

      val x10Trace = new StringBuilder(maxCycles * 12)
      val a0CommitTrace = new StringBuilder(1024)

      while (cycles < maxCycles && !halted) {
        val a0 = c.io.debug_reg_a0.peek().litValue.toLong & 0xffffffffL
        if ((cycles % traceStride) == 0) {
          x10Trace.append(cycles).append(',').append(a0).append('\n')
        }

        if (traceA0Commits && c.io.debug_commit_valid.peek().litToBoolean) {
          val rd = c.io.debug_commit_rd.peek().litValue.toLong
          val isStore = c.io.debug_commit_is_store.peek().litToBoolean
          if (!isStore && rd == 10L) {
            val cpc = c.io.debug_commit_pc.peek().litValue.toLong
            val cop = c.io.debug_commit_op.peek().litValue.toLong
            val cval = c.io.debug_commit_value.peek().litValue.toLong & 0xffffffffL
            a0CommitTrace.append(cycles).append(',').append(f"0x$cpc%08x").append(',').append(f"0x$cop%02x").append(',').append(f"0x$cval%08x").append('\n')
          }
        }

        c.clock.step(1)
        cycles += 1
        halted = c.io.halted.peek().litToBoolean

        if (enableProgress && progressEvery > 0 && cycles >= nextProgress) {
          val pc = c.io.debug_pc.peek().litValue.toLong
          println(f"[progress] cycles=$cycles%d pc=0x$pc%08x a0=0x$a0%08x")
          while (nextProgress <= cycles) nextProgress += progressEvery
        }
      }

      val finalA0 = c.io.debug_reg_a0.peek().litValue.toLong & 0xffffffffL
      val exitCode = finalA0 & 0xffL
      if (((cycles % traceStride) != 0) && cycles > 0) {
        x10Trace.append(cycles).append(',').append(finalA0).append('\n')
      }
      val pw = new PrintWriter(traceOut)
      try {
        pw.print("cycle,a0\n")
        pw.print(x10Trace.result())
      } finally {
        pw.close()
      }

      if (traceA0Commits) {
        val pw2 = new PrintWriter(commitOut)
        try {
          pw2.print("cycle,pc,op,value\n")
          pw2.print(a0CommitTrace.result())
        } finally {
          pw2.close()
        }
      }

      println(f"[quick-result] file=$initFile halted=$halted cycles=$cycles%d final_a0=0x$finalA0%08x exit_code=$exitCode%d trace=$traceOut")

      if (expectedA0 >= 0) {
        assert(exitCode == (expectedA0 & 0xffL), s"expected exit_code(low8 of a0)=$expectedA0, got $exitCode (full a0=$finalA0)")
      }
    }
  }
}
