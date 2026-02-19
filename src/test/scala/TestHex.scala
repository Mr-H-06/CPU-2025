import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

class TestHex extends AnyFlatSpec with ChiselScalatestTester {
  "Core" should "return final result only for a selected data input" in {
    def intProp(name: String, default: Int): Int =
      sys.props.get(name).flatMap(v => scala.util.Try(v.toInt).toOption).getOrElse(default)
    def longPropOpt(name: String): Option[Long] =
      sys.props.get(name).flatMap(v => scala.util.Try(v.toLong).toOption)
    def truthy(v: String): Boolean = {
      val s = v.toLowerCase
      s == "1" || s == "true" || s == "yes" || s == "on"
    }

    val initFile = sys.props.getOrElse("cpu.initFile", "src/test/resources/magic.data")
    val maxCycles = intProp("cpu.maxCycles", 2000000)
    val stepChunk = math.max(1, intProp("cpu.stepChunk", 1024))
    val debugMode = sys.props.get("cpu.debugMode").map(truthy).getOrElse(false)
    val fastStep = sys.props.get("cpu.fastStep").map(truthy).getOrElse(true)
    val noPeekRun = sys.props.get("cpu.noPeekRun").map(truthy).getOrElse(false)
    val pureRun = sys.props.get("cpu.pureRun").map(truthy).getOrElse(true)
    val pureCheckChunk = math.max(1, intProp("cpu.pureCheckChunk", stepChunk))
    val progressEvery = math.max(1, intProp("cpu.progressEvery", 100000))
    val requireHalt = sys.props.get("cpu.requireHalt").map(truthy).getOrElse(false)
    val expectExitCode = longPropOpt("cpu.expectExitCode")

    test(new Core(initFile = initFile, memSize = 131072, memDelay = 4)).withAnnotations(TestBackend.annos) { c =>
      c.clock.setTimeout(0)

      var cycles = 0
      var halted = false
      var preHaltA0 = 0L
      var nextProgress = progressEvery
      var lastCommitCount = 0L
      if (!pureRun) {
        halted = c.io.halted.peek().litToBoolean
        preHaltA0 = c.io.debug_reg_a0.peek().litValue.toLong & 0xffffffffL
        lastCommitCount = c.io.debug_commit_count.peek().litValue.toLong
      }
      if (pureRun || (!debugMode && fastStep && noPeekRun)) {
        // 纯净/极速模式：大步进 + 低频 halt 检查，避免总是跑满 maxCycles
        while (cycles < maxCycles && !halted) {
          val stepNow = math.min(pureCheckChunk, maxCycles - cycles)
          c.clock.step(stepNow)
          cycles += stepNow
          halted = c.io.halted.peek().litToBoolean
          if (halted) {
            preHaltA0 = c.io.debug_reg_a0.peek().litValue.toLong & 0xffffffffL
          }
        }
      } else if (!debugMode && fastStep) {
        // 快速模式：批量 step（更接近 chisel-boom 的 E2E 运行方式）
        while (cycles < maxCycles && !halted) {
          val stepNow = math.min(stepChunk, maxCycles - cycles)
          c.clock.step(stepNow)
          cycles += stepNow
          halted = c.io.halted.peek().litToBoolean
          if (halted) {
            preHaltA0 = c.io.debug_reg_a0.peek().litValue.toLong & 0xffffffffL
          }
        }
      } else {
        // 调试模式：逐周期步进，便于定位
        while (cycles < maxCycles && !halted) {
          val stepNow = math.min(stepChunk, maxCycles - cycles)
          var i = 0
          while (i < stepNow && !halted) {
            preHaltA0 = c.io.debug_reg_a0.peek().litValue.toLong & 0xffffffffL
            c.clock.step(1)
            cycles += 1
            halted = c.io.halted.peek().litToBoolean

            if (debugMode && cycles >= nextProgress) {
              val pc = c.io.debug_pc.peek().litValue.toLong
              val commits = c.io.debug_commit_count.peek().litValue.toLong
              val a0 = c.io.debug_reg_a0.peek().litValue.toLong & 0xffffffffL
              println(f"[DBG] cycles=$cycles%d commits=$commits%d dCommit=${commits - lastCommitCount}%d pc=0x$pc%08x a0=0x$a0%08x")
              lastCommitCount = commits
              while (nextProgress <= cycles) nextProgress += progressEvery
            }
            i += 1
          }
        }
      }

      val finalA0 = c.io.debug_reg_a0.peek().litValue.toLong & 0xffffffffL
      val exitCode = finalA0 & 0xffL
      if (pureRun) {
        // 纯净模式：只关注输入->输出结果，不输出中间调试状态
        println(f"PURE_RESULT file=$initFile halted=$halted cycles=$cycles%d a0=0x$finalA0%08x exit_code=$exitCode%d")
      } else {
        val finalPc = c.io.debug_pc.peek().litValue.toLong
        val headPc = c.io.debug_rob_head_pc.peek().litValue.toLong
        val headOp = c.io.debug_rob_head_op.peek().litValue.toLong
        val headRd = c.io.debug_rob_head_rd.peek().litValue.toLong
        val headValid = c.io.debug_rob_head_valid.peek().litToBoolean
        val headReady = c.io.debug_rob_head_ready.peek().litToBoolean
        val memReady = c.io.debug_mem_ready.peek().litToBoolean
        val commitCountFinal = c.io.debug_commit_count.peek().litValue.toLong
        val ifOutValid = c.io.debug_if_out_valid.peek().litToBoolean
        val ifOutReady = c.io.debug_if_out_ready.peek().litToBoolean
        val ireadValid = c.io.debug_iread_valid.peek().litToBoolean
        val ioutValid = c.io.debug_iout_valid.peek().litToBoolean
        val ioutReady = c.io.debug_iout_ready.peek().litToBoolean
        val robReady = c.io.debug_rob_ready.peek().litToBoolean
        val willFireFinal = c.io.debug_will_fire.peek().litToBoolean

        // 单行最终结果接口；debugMode=true 时会额外输出中间 [DBG] 行
        println(f"RESULT mode=${if (debugMode) "debug" else "result"} fastStep=$fastStep file=$initFile halted=$halted cycles=$cycles%d commits=$commitCountFinal%d final_pc=0x$finalPc%08x head_pc=0x$headPc%08x head_op=0x$headOp%02x head_rd=$headRd%d head_valid=$headValid%s head_ready=$headReady%s mem_ready=$memReady%s if_out_valid=$ifOutValid%s if_out_ready=$ifOutReady%s iread_valid=$ireadValid%s iout_valid=$ioutValid%s iout_ready=$ioutReady%s rob_ready=$robReady%s will_fire=$willFireFinal%s pre_halt_a0=0x$preHaltA0%08x a0=0x$finalA0%08x exit_code=$exitCode%d")
      }

      if (requireHalt) {
        assert(halted, s"did not halt within $maxCycles cycles for $initFile")
      }
      expectExitCode.foreach { e =>
        assert(exitCode == (e & 0xffL), s"expected exit_code=${e & 0xffL}, got $exitCode (a0=0x${finalA0.toHexString})")
      }
    }
  }
}