import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

class BulgarianLongRun extends AnyFlatSpec with ChiselScalatestTester {
  "Core" should "eventually halt on bulgarian.data with high cycle budget" in {
    def truthy(v: String): Boolean = {
      val s = v.toLowerCase
      s == "1" || s == "true" || s == "yes" || s == "on"
    }
    val enabled = sys.props.get("cpu.enableLongRun").exists(truthy)
    val reportOnly = sys.props.get("cpu.reportOnly").exists(truthy)
    val requireHalt = sys.props.get("cpu.requireHalt").map(truthy).getOrElse(true)
    if (!enabled) {
      cancel("LongRun disabled by default; set -Dcpu.enableLongRun=true to enable")
    }
    test(new Core(initFile = "src/test/resources/bulgarian.data", memSize = 131072, memDelay = 4)).withAnnotations(TestBackend.annos) { c =>
      val maxCycles = sys.props.get("cpu.longRunMaxCycles").flatMap(v => scala.util.Try(v.toInt).toOption).getOrElse(1500000)
      val sampleStride = sys.props.get("cpu.sampleStrideLong").flatMap(v => scala.util.Try(v.toInt).toOption).getOrElse(1024)
      val progressEvery = sys.props.get("cpu.progressEvery").flatMap(v => scala.util.Try(v.toInt).toOption).getOrElse(50000)
      val noCommitLimit = sys.props.get("cpu.noCommitLimit").flatMap(v => scala.util.Try(v.toInt).toOption).getOrElse(120000)
      val maxStagnantPc = sys.props.get("cpu.maxStagnantPc").flatMap(v => scala.util.Try(v.toInt).toOption).getOrElse(120000)
      val loopWindow = sys.props.get("cpu.loopWindow").flatMap(v => scala.util.Try(v.toInt).toOption).getOrElse(64)
      val maxLoopRepeats = sys.props.get("cpu.maxLoopRepeats").flatMap(v => scala.util.Try(v.toInt).toOption).getOrElse(2000)
      val wallTimeoutSec = sys.props.get("cpu.wallTimeoutSec").flatMap(v => scala.util.Try(v.toInt).toOption).getOrElse(1200)
      val stagnantSamplesLimit = math.max(8, maxStagnantPc / math.max(1, sampleStride))
      c.clock.setTimeout(maxCycles + 2000)
      val wallStartNs = System.nanoTime()
      var cycles = 0
      var noCommitCycles = 0
      var lastPc: BigInt = -1
      var stagnantPc = 0
      var loopRepeats = 0
      var pcWindow = Vector.empty[BigInt]
      var nextProgress = progressEvery
      var lastCommittedA0 = c.io.debug_reg_a0.peek().litValue.toLong & 0xffffffffL
      var lastCommittedA0Cycle = 0
      var lastCommittedA0CommitCount = c.io.debug_commit_count.peek().litValue.toLong
      var seenExpectedA0 = lastCommittedA0 == 159L
      while (cycles < maxCycles && !c.io.halted.peek().litToBoolean) {
        if (Thread.currentThread().isInterrupted) {
          fail(s"bulgarian aborted by interrupt at cycles=$cycles commits=${c.io.debug_commit_count.peek().litValue}")
        }
        val wallSecNow = (System.nanoTime() - wallStartNs).toDouble / 1e9
        if (wallTimeoutSec > 0 && wallSecNow >= wallTimeoutSec) {
          val pcNow = c.io.debug_pc.peek().litValue.toLong
          val commitsNow = c.io.debug_commit_count.peek().litValue.toLong
          fail(f"bulgarian wall-time timeout: ${wallSecNow}%.1fs at cycles=$cycles%d commits=$commitsNow%d pc=0x$pcNow%08x")
        }

        val pc = c.io.debug_pc.peek().litValue
        val commitValid = c.io.debug_commit_valid.peek().litToBoolean
        if (commitValid) {
          val rd = c.io.debug_commit_rd.peek().litValue.toLong
          if (rd == 10L) {
            lastCommittedA0 = c.io.debug_commit_value.peek().litValue.toLong & 0xffffffffL
            lastCommittedA0Cycle = cycles
            lastCommittedA0CommitCount = c.io.debug_commit_count.peek().litValue.toLong
            if (lastCommittedA0 == 159L) seenExpectedA0 = true
          }
        }
        val commitsBefore = c.io.debug_commit_count.peek().litValue.toInt
        if (pc == lastPc) stagnantPc += 1 else stagnantPc = 0
        lastPc = pc
        pcWindow = (pcWindow :+ pc).takeRight(loopWindow)
        if (pcWindow.size == loopWindow && loopWindow % 2 == 0) {
          val half = loopWindow / 2
          val first = pcWindow.take(half)
          val second = pcWindow.drop(half)
          if (first == second) loopRepeats += 1 else loopRepeats = 0
        }
        if (stagnantPc >= stagnantSamplesLimit) {
          val approxCycles = stagnantPc * sampleStride
          fail(f"bulgarian early-stop: PC stuck at 0x${pc.toLong}%08x for about $approxCycles%d cycles")
        }
        if (loopRepeats >= maxLoopRepeats) {
          fail(f"bulgarian early-stop: short PC loop near 0x${pc.toLong}%08x repeats=$loopRepeats%d")
        }

        val stepNow = math.min(sampleStride, maxCycles - cycles)
        try {
          c.clock.step(stepNow)
        } catch {
          case _: InterruptedException =>
            fail(s"bulgarian interrupted during stepping at cycles=$cycles")
          case t: Throwable if t.getMessage != null && t.getMessage.toLowerCase.contains("interrupted") =>
            fail(s"bulgarian aborted: ${t.getMessage}")
        }
        cycles += stepNow

        val commitsAfter = c.io.debug_commit_count.peek().litValue.toInt
        if (commitsAfter > commitsBefore) {
          noCommitCycles = 0
        } else {
          noCommitCycles += stepNow
        }
        if (noCommitCycles >= noCommitLimit) {
          fail(f"bulgarian early-stop: no commit for $noCommitCycles%d cycles at pc=0x${pc.toLong}%08x")
        }
        if (progressEvery > 0 && cycles >= nextProgress) {
          val ipc = commitsAfter.toDouble / math.max(1, cycles).toDouble
          val wallSec = (System.nanoTime() - wallStartNs).toDouble / 1e9
          val simKHz = if (wallSec > 0) cycles.toDouble / wallSec / 1000.0 else 0.0
          println(f"[bulgarian] cycles=$cycles%d commits=$commitsAfter%d ipc=$ipc%.4f pc=0x${pc.toLong}%08x wall=${wallSec}%.1fs sim=${simKHz}%.1f kHz")
          while (nextProgress <= cycles) {
            nextProgress += progressEvery
          }
        }
      }
      val halted = c.io.halted.peek().litToBoolean
      val commits = c.io.debug_commit_count.peek().litValue.toLong
      val a0Now = c.io.debug_reg_a0.peek().litValue.toLong & 0xffffffffL
      println(f"bulgarian cycles=$cycles%d halted=$halted commits=$commits%d a0_now=0x$a0Now%08x a0_last_commit=0x$lastCommittedA0%08x last_a0_cycle=$lastCommittedA0Cycle%d last_a0_commit_count=$lastCommittedA0CommitCount%d seen_expected_a0=$seenExpectedA0")
      if (!reportOnly) {
        if (requireHalt) {
          assert(halted, s"bulgarian did not halt within $maxCycles cycles; a0_now=0x${a0Now.toHexString}, a0_last_commit=0x${lastCommittedA0.toHexString}")
        }
        assert(seenExpectedA0, s"bulgarian expected pre-halt x10(a0)=159 at least once, but never observed; lastCommittedA0=$lastCommittedA0 (a0_now=$a0Now)")
      }
    }
  }
}
