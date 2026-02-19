import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

class AllDataDebugSpec extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "Core over all data programs"

  private def intProp(name: String): Option[Int] =
    sys.props.get(name).flatMap(v => scala.util.Try(v.toInt).toOption)

  private val defaultMaxCycles = intProp("cpu.maxCycles").getOrElse(120000)
  private val defaultMaxStagnant = intProp("cpu.maxStagnant").getOrElse(1000)
  private val includeLongPrograms =
    sys.props.get("cpu.includeLongData").exists(v => v == "1" || v.equalsIgnoreCase("true"))

  private val programs = Seq(
    "src/test/resources/array_test1.data",
    "src/test/resources/array_test2.data",
    "src/test/resources/basicopt1.data",
    "src/test/resources/bulgarian.data",
    "src/test/resources/expr.data",
    "src/test/resources/halt.data",
    "src/test/resources/hanoi.data",
    "src/test/resources/lvalue2.data",
    "src/test/resources/magic.data",
    "src/test/resources/manyarguments.data",
    "src/test/resources/multiarray.data",
    "src/test/resources/naive.data",
    "src/test/resources/qsort.data",
    "src/test/resources/simple.data",
    "src/test/resources/test.data",
    "src/test/resources/test2.data"
  )

  private val longPrograms = Set(
    "src/test/resources/basicopt1.data",
    "src/test/resources/bulgarian.data",
    "src/test/resources/hanoi.data",
    "src/test/resources/qsort.data"
  )

  private val selectedPrograms =
    if (includeLongPrograms) programs else programs.filterNot(longPrograms.contains)

  private val limits = Map(
    "src/test/resources/array_test2.data" -> (20000, 40),
    "src/test/resources/basicopt1.data" -> (2000000, 20000),
    "src/test/resources/bulgarian.data" -> (2000000, 20000),
    "src/test/resources/hanoi.data" -> (2000000, 20000),
    "src/test/resources/magic.data" -> (2000000, 20000),
    "src/test/resources/qsort.data" -> (2000000, 20000)
  )

  private def runProgram(path: String, defaultMax: Int = defaultMaxCycles, defaultStagnant: Int = defaultMaxStagnant): Unit = {
    val (maxCycles, maxStagnant) = limits.getOrElse(path, (defaultMax, defaultStagnant))
    val isLong = longPrograms.contains(path)
    val stride = if (isLong) intProp("cpu.sampleStrideLong").getOrElse(16) else intProp("cpu.sampleStride").getOrElse(1)
    val stagnantThreshold = if (stride <= 1) maxStagnant else math.max(8, maxStagnant / stride)
    test(new Core(initFile = path, memSize = 131072, memDelay = 4)).withAnnotations(TestBackend.annos) { c =>
      c.clock.setTimeout(maxCycles + 2000)
      var cycles = 0
      var halted = false
      var lastPc: BigInt = -1
      var stagnantNoCommit = 0 // sample-based stagnant-without-commit count
      var lastCommitCount = c.io.debug_commit_count.peek().litValue

      while (cycles < maxCycles && !halted) {
        val pc = c.io.debug_pc.peek().litValue
        val commitCountNow = c.io.debug_commit_count.peek().litValue
        val noCommit = commitCountNow == lastCommitCount
        if (pc == lastPc && noCommit) stagnantNoCommit += 1 else stagnantNoCommit = 0
        lastPc = pc
        lastCommitCount = commitCountNow

        if (stagnantNoCommit >= stagnantThreshold) {
          val approxCycles = stagnantNoCommit * stride
          fail(s"[$path] PC stuck at 0x${pc.toLong}%08x for about $approxCycles cycles (stride=$stride)")
        }

        val stepNow = math.min(stride, maxCycles - cycles)
        c.clock.step(stepNow)
        cycles += stepNow
        halted = c.io.halted.peek().litToBoolean
      }

      val lastPcHex = if (lastPc >= 0) f"0x${lastPc.toLong}%08x" else lastPc.toString
      assert(halted, s"[$path] Core did not halt within $maxCycles cycles; last PC=$lastPcHex")
    }
  }

  it should "halt cleanly on every data program" in {
    if (!includeLongPrograms) {
      info("Skipping long programs (set -Dcpu.includeLongData=true to include)")
    }
    selectedPrograms.foreach { path =>
      info(s"Running $path")
      runProgram(path)
    }
  }
}
