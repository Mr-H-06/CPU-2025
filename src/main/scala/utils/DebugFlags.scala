package utils

object DebugFlags {
  private def truthy(value: String): Boolean = {
    val lower = value.toLowerCase
    lower == "1" || lower == "true" || lower == "yes" || lower == "on"
  }

  val enableVerbosePrintf: Boolean =
    sys.props.get("cpu.verbosePrintf").exists(truthy)
}
