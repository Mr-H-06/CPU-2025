import chiseltest.simulator.VerilatorBackendAnnotation
import chiseltest.simulator.VerilatorFlags
import firrtl.annotations.Annotation

object TestBackend {
  private def truthy(v: String): Boolean = {
    val s = v.toLowerCase
    s == "1" || s == "true" || s == "yes" || s == "on"
  }

  // Default to non-Verilator for stability. Enable explicitly with -Dcpu.useVerilator=true.
  val useVerilator: Boolean =
    sys.props.get("cpu.useVerilator").map(truthy).getOrElse(false)

  // Some environments hit intermittent PCH build races with Verilator+make -j.
  // Keep this opt-in because older Verilator versions do not support --no-pch.
  private val disablePch: Boolean =
    sys.props.get("cpu.verilatorNoPch").map(truthy).getOrElse(false)

  val annos: Seq[Annotation] =
    if (useVerilator) {
      val extra = if (disablePch) Seq(VerilatorFlags(Seq("--no-pch"))) else Seq.empty
      Seq(VerilatorBackendAnnotation) ++ extra
    } else Seq.empty
}
