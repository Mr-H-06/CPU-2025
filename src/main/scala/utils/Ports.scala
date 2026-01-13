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

object AluOpEnum extends ChiselEnum {
  val ADD = Value
  val SUB = Value
  val AND = Value
  val OR = Value
  val XOR = Value
  val LL = Value
  val RL = Value
  val RA = Value
  val SLT = Value
  val SLTU = Value
  val EQ = Value
  val NE = Value
  val GE = Value
  val GEU = Value
}

class MemInput extends Bundle {
  val op = MemOpEnum()
  val value = UInt(32.W)
  val address = UInt(32.W)
  val index = UInt(5.W)
}

class AluExecBits extends Bundle {
  val op = AluOpEnum()
  val op1 = UInt(32.W)
  val op2 = UInt(32.W)
  val tag = UInt(5.W)
}

class AluResultBits extends Bundle {
  val value = UInt(32.W)
  val tag = UInt(5.W)
}