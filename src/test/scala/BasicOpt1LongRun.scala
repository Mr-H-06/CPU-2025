import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

class BasicOpt1LongRun extends AnyFlatSpec with ChiselScalatestTester {
  "Core" should "eventually halt on basicopt1.data with high cycle budget" in {
    def truthy(v: String): Boolean = {
      val s = v.toLowerCase
      s == "1" || s == "true" || s == "yes" || s == "on"
    }
    val enabled = sys.props.get("cpu.enableLongRun").exists(truthy)
    val reportOnly = sys.props.get("cpu.reportOnly").exists(truthy)
    if (!enabled) {
      cancel("LongRun disabled by default; set -Dcpu.enableLongRun=true to enable")
    }
    test(new Core(initFile = "src/test/resources/basicopt1.data", memSize = 131072, memDelay = 4)).withAnnotations(TestBackend.annos) { c =>
      val maxCycles = sys.props.get("cpu.longRunMaxCycles").flatMap(v => scala.util.Try(v.toInt).toOption).getOrElse(2200000)
      val sampleStride = sys.props.get("cpu.sampleStrideLong").flatMap(v => scala.util.Try(v.toInt).toOption).getOrElse(1024)
      val progressEvery = sys.props.get("cpu.progressEvery").flatMap(v => scala.util.Try(v.toInt).toOption).getOrElse(200000)
      val noCommitLimit = sys.props.get("cpu.noCommitLimit").flatMap(v => scala.util.Try(v.toInt).toOption).getOrElse(120000)
      val maxStagnantPc = sys.props.get("cpu.maxStagnantPc").flatMap(v => scala.util.Try(v.toInt).toOption).getOrElse(12000)
      val loopWindow = sys.props.get("cpu.loopWindow").flatMap(v => scala.util.Try(v.toInt).toOption).getOrElse(64)
      val maxLoopRepeats = sys.props.get("cpu.maxLoopRepeats").flatMap(v => scala.util.Try(v.toInt).toOption).getOrElse(400)
      val wallTimeoutSec = sys.props.get("cpu.wallTimeoutSec").flatMap(v => scala.util.Try(v.toInt).toOption).getOrElse(1200)
      val quickMode = sys.props.get("cpu.quickMode").exists(truthy)
      val quickChunk = sys.props.get("cpu.quickChunk").flatMap(v => scala.util.Try(v.toInt).toOption).getOrElse(65536)
      val stagnantSamplesLimit = math.max(8, maxStagnantPc / math.max(1, sampleStride))
      c.clock.setTimeout(maxCycles + 2000)
      val wallStartNs = System.nanoTime()
      var cycles = 0
      var commits = c.io.debug_commit_count.peek().litValue.toInt
      var noCommitCycles = 0
      var lastPc: BigInt = -1
      var stagnantPc = 0
      var loopRepeats = 0
      var pcWindow = Vector.empty[BigInt]
      var nextProgress = progressEvery
      var lastCommittedA0 = c.io.debug_reg_a0.peek().litValue.toLong & 0xffffffffL
      var lastCommittedA0Cycle = 0
      var lastCommittedA0CommitCount = c.io.debug_commit_count.peek().litValue.toLong

      if (quickMode) {
        var haltedQuick = c.io.halted.peek().litToBoolean
        while (cycles < maxCycles && !haltedQuick) {
          if (Thread.currentThread().isInterrupted) {
            fail(s"basicopt1 quick aborted by interrupt at cycles=$cycles")
          }
          val wallSecNow = (System.nanoTime() - wallStartNs).toDouble / 1e9
          if (wallTimeoutSec > 0 && wallSecNow >= wallTimeoutSec) {
            val pcNow = c.io.debug_pc.peek().litValue.toLong
            val commitsNow = c.io.debug_commit_count.peek().litValue.toLong
            fail(f"basicopt1 quick wall-time timeout: ${wallSecNow}%.1fs at cycles=$cycles%d commits=$commitsNow%d pc=0x$pcNow%08x")
          }

          val stepNow = math.min(math.max(1, quickChunk), maxCycles - cycles)
          try {
            c.clock.step(stepNow)
          } catch {
            case _: InterruptedException =>
              fail(s"basicopt1 quick interrupted during stepping at cycles=$cycles")
            case t: Throwable if t.getMessage != null && t.getMessage.toLowerCase.contains("interrupted") =>
              fail(s"basicopt1 quick aborted: ${t.getMessage}")
          }
          cycles += stepNow
          haltedQuick = c.io.halted.peek().litToBoolean

          if (progressEvery > 0 && cycles >= nextProgress) {
            val commitsNow = c.io.debug_commit_count.peek().litValue.toLong
            val pcNow = c.io.debug_pc.peek().litValue.toLong
            val wallSec = (System.nanoTime() - wallStartNs).toDouble / 1e9
            val simKHz = if (wallSec > 0) cycles.toDouble / wallSec / 1000.0 else 0.0
            println(f"[basicopt1-quick] cycles=$cycles%d commits=$commitsNow%d pc=0x$pcNow%08x wall=${wallSec}%.1fs sim=${simKHz}%.1f kHz")
            while (nextProgress <= cycles) nextProgress += progressEvery
          }
        }

        val halted = c.io.halted.peek().litToBoolean
        val commitsNow = c.io.debug_commit_count.peek().litValue.toLong
        val a0Now = c.io.debug_reg_a0.peek().litValue.toLong & 0xffffffffL
        println(f"basicopt1-quick cycles=$cycles%d halted=$halted commits=$commitsNow%d a0_now=0x$a0Now%08x")
        if (!reportOnly) {
          assert(halted, s"basicopt1 quick did not halt within $maxCycles cycles; a0_now=0x${a0Now.toHexString}")
          val a0Mod256 = a0Now & 0xffL
          assert(a0Mod256 == 88L, s"basicopt1 quick expected halt-time x10%256==88, got x10=0x${a0Now.toHexString} (x10%256=$a0Mod256)")
        }
      } else {
      while (cycles < maxCycles && !c.io.halted.peek().litToBoolean) {
        if (Thread.currentThread().isInterrupted) {
          fail(s"basicopt1 aborted by interrupt at cycles=$cycles commits=${c.io.debug_commit_count.peek().litValue}")
        }
        val wallSecNow = (System.nanoTime() - wallStartNs).toDouble / 1e9
        if (wallTimeoutSec > 0 && wallSecNow >= wallTimeoutSec) {
          val pcNow = c.io.debug_pc.peek().litValue.toLong
          val commitsNow = c.io.debug_commit_count.peek().litValue.toLong
          fail(f"basicopt1 wall-time timeout: ${wallSecNow}%.1fs at cycles=$cycles%d commits=$commitsNow%d pc=0x$pcNow%08x")
        }

        val pc = c.io.debug_pc.peek().litValue
        val commitValid = c.io.debug_commit_valid.peek().litToBoolean
        if (commitValid) {
          val rd = c.io.debug_commit_rd.peek().litValue.toLong
          if (rd == 10L) {
            lastCommittedA0 = c.io.debug_commit_value.peek().litValue.toLong & 0xffffffffL
            lastCommittedA0Cycle = cycles
            lastCommittedA0CommitCount = c.io.debug_commit_count.peek().litValue.toLong
          }
        }
        val commitsBefore = c.io.debug_commit_count.peek().litValue.toInt

        if (pc == lastPc) stagnantPc += 1 else stagnantPc = 0
        lastPc = pc

        pcWindow = (pcWindow :+ pc).takeRight(loopWindow)
        if (pcWindow.size == loopWindow) {
          val half = loopWindow / 2
          val first = pcWindow.take(half)
          val second = pcWindow.drop(half)
          if (first == second) loopRepeats += 1 else loopRepeats = 0
        }

        if (noCommitCycles >= noCommitLimit) {
          fail(f"basicopt1 early-stop: no commit for $noCommitCycles%d cycles at pc=0x${pc.toLong}%08x commits=$commits%d")
        }
        if (stagnantPc >= stagnantSamplesLimit) {
          val approxCycles = stagnantPc * sampleStride
          fail(f"basicopt1 early-stop: PC stuck at 0x${pc.toLong}%08x for about $approxCycles%d cycles commits=$commits%d")
        }
        if (loopRepeats >= maxLoopRepeats) {
          fail(f"basicopt1 early-stop: short PC loop near 0x${pc.toLong}%08x repeats=$loopRepeats%d commits=$commits%d")
        }

        val stepNow = math.min(sampleStride, maxCycles - cycles)
        try {
          c.clock.step(stepNow)
        } catch {
          case _: InterruptedException =>
            fail(s"basicopt1 interrupted during stepping at cycles=$cycles")
          case t: Throwable if t.getMessage != null && t.getMessage.toLowerCase.contains("interrupted") =>
            fail(s"basicopt1 aborted: ${t.getMessage}")
        }
        cycles += stepNow

        val commitsAfter = c.io.debug_commit_count.peek().litValue.toInt
        val delta = commitsAfter - commitsBefore
        if (delta > 0) {
          commits = commitsAfter
          noCommitCycles = 0
        } else {
          noCommitCycles += stepNow
        }

        if (progressEvery > 0 && cycles >= nextProgress) {
          val ipc = commits.toDouble / math.max(1, cycles).toDouble
          val wallSec = (System.nanoTime() - wallStartNs).toDouble / 1e9
          val simKHz = if (wallSec > 0) cycles.toDouble / wallSec / 1000.0 else 0.0
          println(f"[basicopt1] cycles=$cycles%d commits=$commits%d ipc=$ipc%.4f pc=0x${pc.toLong}%08x wall=${wallSec}%.1fs sim=${simKHz}%.1f kHz")
          while (nextProgress <= cycles) {
            nextProgress += progressEvery
          }
        }
      }
      val halted = c.io.halted.peek().litToBoolean
      val a0Now = c.io.debug_reg_a0.peek().litValue.toLong & 0xffffffffL
      println(f"basicopt1 cycles=$cycles%d halted=$halted commits=$commits%d a0_now=0x$a0Now%08x a0_last_commit=0x$lastCommittedA0%08x last_a0_cycle=$lastCommittedA0Cycle%d last_a0_commit_count=$lastCommittedA0CommitCount%d")
      if (!reportOnly) {
        assert(halted, s"basicopt1 did not halt within $maxCycles cycles; a0_now=0x${a0Now.toHexString}, a0_last_commit=0x${lastCommittedA0.toHexString}")
        val a0Mod256 = a0Now & 0xffL
        assert(a0Mod256 == 88L, s"basicopt1 expected halt-time x10%256==88, got x10=0x${a0Now.toHexString} (x10%256=$a0Mod256, lastCommittedA0=$lastCommittedA0)")
      }
      }
    }
  }
}
