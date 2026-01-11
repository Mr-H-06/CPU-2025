package utils

import chisel3._
import chisel3.util._
import chisel3.experimental._

class CDBData extends Bundle {
  val index = UInt(5.W)
  val value = UInt(32.W)
}

object MemOpEnum extends ChiselEnum {
  val lb = Value
  val lbu = Value
  val lh = Value
  val lhu = Value
  val lw = Value
  val sb = Value
  val sh = Value
  val sw = Value
}