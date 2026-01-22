import chisel3._
import chisel3.util._
import utils._

class ALU extends Module {
  val io = IO(new Bundle {
    val clear = Input(Bool())
    val exec_valid = Input(Bool())
    val exec_bits = Input(new AluExecBits())
    val CDB_ready = Input(Bool())
    val ready = Output(Bool())
    val result_valid = Output(Bool())
    val result_bits = Output(new AluResultBits())
  })

  // Local cycle counter for targeted debug prints
  private val dbgCycle = RegInit(0.U(32.W))
  dbgCycle := dbgCycle + 1.U

  // Registers for result
  val resultValidReg = RegInit(false.B)
  val resultBitsReg = RegInit(0.U.asTypeOf(new AluResultBits()))

  io.result_valid := resultValidReg
  io.result_bits := resultBitsReg
  io.ready := !resultValidReg

  when(io.clear) {
    resultValidReg := false.B
    resultBitsReg := 0.U.asTypeOf(new AluResultBits())
  }.otherwise {
    when(io.exec_valid && io.ready) {
      resultBitsReg.tag := io.exec_bits.tag
      switch(io.exec_bits.op) {
        is(AluOpEnum.ADD) {
          resultBitsReg.value := io.exec_bits.op1 + io.exec_bits.op2
        }
        is(AluOpEnum.SUB) {
          resultBitsReg.value := io.exec_bits.op1 - io.exec_bits.op2
        }
        is(AluOpEnum.AND) {
          resultBitsReg.value := io.exec_bits.op1 & io.exec_bits.op2
        }
        is(AluOpEnum.OR) {
          resultBitsReg.value := io.exec_bits.op1 | io.exec_bits.op2
        }
        is(AluOpEnum.XOR) {
          resultBitsReg.value := io.exec_bits.op1 ^ io.exec_bits.op2
        }
        is(AluOpEnum.SLT) {
          resultBitsReg.value := (io.exec_bits.op1.asSInt < io.exec_bits.op2.asSInt).asUInt
        }
        is(AluOpEnum.SLTU) {
          resultBitsReg.value := (io.exec_bits.op1 < io.exec_bits.op2).asUInt
        }
        is(AluOpEnum.LL) {
          resultBitsReg.value := io.exec_bits.op1 << io.exec_bits.op2(4, 0) // assuming 5-bit shift
        }
        is(AluOpEnum.RL) {
          resultBitsReg.value := io.exec_bits.op1 >> io.exec_bits.op2(4, 0)
        }
        is(AluOpEnum.RA) {
          resultBitsReg.value := (io.exec_bits.op1.asSInt >> io.exec_bits.op2(4, 0)).asUInt
        }
        is(AluOpEnum.EQ) {
          resultBitsReg.value := (io.exec_bits.op1 === io.exec_bits.op2).asUInt
        }
        is(AluOpEnum.NE) {
          resultBitsReg.value := (io.exec_bits.op1 =/= io.exec_bits.op2).asUInt
        }
        is(AluOpEnum.GE) {
          resultBitsReg.value := (io.exec_bits.op1.asSInt >= io.exec_bits.op2.asSInt).asUInt
        }
        is(AluOpEnum.GEU) {
          resultBitsReg.value := (io.exec_bits.op1 >= io.exec_bits.op2).asUInt
        }
      }
      resultValidReg := true.B
    }.otherwise {
      resultValidReg := false.B
    }
  }

  // Targeted debug to trace compare operations during array_test2 hang
  val aluDbg = dbgCycle >= 4600.U && dbgCycle < 4910.U
  when(aluDbg && io.exec_valid && (io.exec_bits.op === AluOpEnum.GE || io.exec_bits.op === AluOpEnum.GEU)) {
    printf("[ALUDBG] cycle=%d op=%d op1=%x op2=%x res=%x tag=%d\n",
      dbgCycle, io.exec_bits.op.asUInt, io.exec_bits.op1, io.exec_bits.op2, resultBitsReg.value, io.exec_bits.tag)
  }
}