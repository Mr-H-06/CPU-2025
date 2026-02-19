import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import java.nio.file.{Files, Paths}
import scala.collection.JavaConverters._

class BoomStyleFastSpec extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "Core with boom-style fast batch stepping"

  private def intProp(name: String, default: Int): Int =
    sys.props.get(name).flatMap(v => scala.util.Try(v.toInt).toOption).getOrElse(default)

  private def truthy(name: String, default: Boolean): Boolean =
    sys.props
      .get(name)
      .map(v => {
        val s = v.toLowerCase
        s == "1" || s == "true" || s == "yes" || s == "on"
      })
      .getOrElse(default)

  private def strProp(name: String, default: String): String =
    sys.props.get(name).map(_.trim).filter(_.nonEmpty).getOrElse(default)

  // 仿照 chisel-boom: 默认先跑快速 e2e 集合，长程序按需开启
  private val quickPrograms = Seq(
    "naive",
    "simple",
    "halt",
    "statement_test",
    "manyarguments",
    "gcd",
    "test2",
    "array_test1",
    "lvalue2",
    "array_test2"
  )

  private val largePrograms = Seq(
    "expr",
    "multiarray",
    "hanoi",
    "magic",
    "basicopt1",
    "bulgarian",
    "qsort",
    "test"
  )

  // 来自你给的仓库（chisel-boom）常见 simtests 命名风格，优先用于快速定位
  private val repoStylePrograms = Seq(
    "naive",
    "statement_test",
    "manyarguments",
    "expr",
    "gcd",
    "magic",
    "hanoi",
    "basicopt1",
    "bulgarian",
    "qsort",
    "test"
  )

  private val expectedExit = Map(
    "naive" -> 94,
    "simple" -> 123,
    "manyarguments" -> 40,
    "hanoi" -> 20,
    "basicopt1" -> 88,
    "bulgarian" -> 159,
    "test" -> 206,
    "statement_test" -> 50,
    "magic" -> 106,
    "gcd" -> 178,
    "expr" -> 58
  )

  private def toDataPath(nameOrPath: String): String = {
    if (nameOrPath.endsWith(".data")) nameOrPath
    else s"src/test/resources/$nameOrPath.data"
  }

  private def testNameFromPath(path: String): String =
    path.split('/').last.stripSuffix(".data")

  private def allDataPrograms(): Seq[String] = {
    val root = Paths.get("src/test/resources")
    if (!Files.exists(root)) return quickPrograms ++ largePrograms
    Files
      .list(root)
      .iterator()
      .asScala
      .filter(p => Files.isRegularFile(p) && p.getFileName.toString.endsWith(".data"))
      .map(p => p.getFileName.toString.stripSuffix(".data"))
      .toSeq
      .sorted
  }

  it should "run selected programs quickly" in {
    val profile = strProp("cpu.profile", "repo").toLowerCase
    val includeLarge = truthy("cpu.includeLarge", false)
    val selectedByProfile = profile match {
      case "quick" => if (includeLarge) quickPrograms ++ largePrograms else quickPrograms
      case "full"  => allDataPrograms()
      case _        => repoStylePrograms // 默认走仓库风格点集
    }
    val only = sys.props.get("cpu.only").map(_.split(',').map(_.trim).filter(_.nonEmpty).toSeq).getOrElse(selectedByProfile)
    val selected = only.map(toDataPath)

    val maxCyclesQuick = intProp("cpu.maxCyclesQuick", 250000)
    val maxCyclesLarge = intProp("cpu.maxCyclesLarge", 2000000)
    val batchSizeQuick = math.max(1, intProp("cpu.batchSizeQuick", 4096))
    val batchSizeLarge = math.max(1, intProp("cpu.batchSizeLarge", intProp("cpu.batchSize", 65536)))
    val stopCommits = math.max(0, intProp("cpu.stopCommits", 0))
    val wallTimeoutSec = math.max(0, intProp("cpu.wallTimeoutSec", 0))
    val minProgressCommits = math.max(0, intProp("cpu.minProgressCommits", 0))
    val progressEvery = intProp("cpu.progressEvery", 0)
    val requireHalt = truthy("cpu.requireHalt", true)
    val checkExit = truthy("cpu.checkExit", true)
    val requireStopCommitsReached = truthy("cpu.requireStopCommitsReached", false)

    selected.foreach { path =>
      val name = testNameFromPath(path)
      val maxCycles = if (largePrograms.contains(name)) maxCyclesLarge else maxCyclesQuick
      val batchSize = if (largePrograms.contains(name)) batchSizeLarge else batchSizeQuick
      info(s"[boom-fast] running $path")

      test(new Core(initFile = path, memSize = 131072, memDelay = 4)).withAnnotations(TestBackend.annos) { c =>
        c.clock.setTimeout(0)

        var cycles = 0
        var halted = c.io.halted.peek().litToBoolean
        var preHaltA0 = c.io.debug_reg_a0.peek().litValue.toLong & 0xffffffffL
        var nextProgress = if (progressEvery > 0) progressEvery else Int.MaxValue
        var commitsNow = c.io.debug_commit_count.peek().litValue.toLong
        val startCommits = commitsNow
        val wallStartNs = System.nanoTime()
        var hitWallTimeout = false

        while (cycles < maxCycles && !halted && (stopCommits == 0 || commitsNow < stopCommits.toLong)) {
          if (wallTimeoutSec > 0) {
            val wallSec = (System.nanoTime() - wallStartNs).toDouble / 1e9
            if (wallSec >= wallTimeoutSec) {
              hitWallTimeout = true
            }
          }
          if (hitWallTimeout) {
            // 时间预算到期，提前退出本次快测
            cycles = maxCycles
          } else {
          preHaltA0 = c.io.debug_reg_a0.peek().litValue.toLong & 0xffffffffL
          val stepNow = math.min(batchSize, maxCycles - cycles)
          c.clock.step(stepNow)
          cycles += stepNow
          halted = c.io.halted.peek().litToBoolean
          commitsNow = c.io.debug_commit_count.peek().litValue.toLong

          if (cycles >= nextProgress) {
            val pc = c.io.debug_pc.peek().litValue.toLong
            val commits = commitsNow
            val a0Now = c.io.debug_reg_a0.peek().litValue.toLong & 0xffffffffL
            println(f"[boom-fast] name=$name cycles=$cycles%d commits=$commits%d pc=0x$pc%08x a0=0x$a0Now%08x")
            while (nextProgress <= cycles) nextProgress += progressEvery
          }
          }
        }

        val finalA0 = c.io.debug_reg_a0.peek().litValue.toLong & 0xffffffffL
        val exitCode = (finalA0 & 0xffL).toInt
        val finalPc = c.io.debug_pc.peek().litValue.toLong
        val progressCommits = commitsNow - startCommits
        println(f"RESULT name=$name halted=$halted cycles=$cycles%d commits=$commitsNow%d progress_commits=$progressCommits%d wall_timeout_hit=$hitWallTimeout final_pc=0x$finalPc%08x pre_halt_a0=0x$preHaltA0%08x a0=0x$finalA0%08x exit_code=$exitCode%d")

        if (requireHalt) {
          assert(halted, s"[$name] did not halt within budget (maxCycles=$maxCycles, wallTimeoutSec=$wallTimeoutSec)")
        }
        if (requireStopCommitsReached && stopCommits > 0) {
          assert(commitsNow >= stopCommits.toLong, s"[$name] commits=$commitsNow did not reach stopCommits=$stopCommits")
        }
        if (minProgressCommits > 0) {
          assert(progressCommits >= minProgressCommits, s"[$name] progress_commits=$progressCommits < minProgressCommits=$minProgressCommits")
        }
        if (checkExit && expectedExit.contains(name)) {
          assert(exitCode == expectedExit(name), s"[$name] expected exit_code=${expectedExit(name)}, got $exitCode")
        }
      }
    }
  }
}
