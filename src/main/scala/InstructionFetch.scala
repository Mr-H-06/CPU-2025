import chisel3._
import chisel3.util._
import utils._

class IFDecoded extends Bundle {
  val instr = UInt(32.W)
  val pc = UInt(32.W)
  val rd = UInt(5.W)
  val rs1 = UInt(5.W)
  val rs2 = UInt(5.W)
  val imm = UInt(32.W)
  val opcode = UInt(7.W)
  val funct3 = UInt(3.W)
  val funct7 = UInt(7.W)
}

class InstructionFetch(queueDepth: Int = 4) extends Module {
  val io = IO(new Bundle {
    // control
    val resetPC = Input(UInt(32.W))
    val resetValid = Input(Bool())
    val clear = Input(Bool())

    // memory side (to external instruction memory)
    val mem_iread_address = Output(UInt(32.W))
    val mem_iread_valid = Output(Bool())
    val mem_iout_ready = Input(Bool())
    val mem_iout_valid = Input(Bool())
    val mem_iout_data = Input(UInt(32.W))

    // downstream consumer (ROB/RS/LSB)
    val out = Decoupled(new IFDecoded)
  })

  val pcReg = RegInit(0.U(32.W))
  val reqPcReg = RegInit(0.U(32.W))
  val issuing = WireDefault(false.B)
  val busy = RegInit(false.B)

  when(io.clear || io.resetValid) {
    pcReg := Mux(io.resetValid, io.resetPC & ~3.U, 0.U)
  }.elsewhen(issuing) {
    pcReg := pcReg + 4.U
  }

  // request when queue can accept and memory ready
  val q = Module(new Queue(new IFDecoded, queueDepth))
  q.io.deq <> io.out

  val canRequest = q.io.enq.ready && io.mem_iout_ready && !busy
  issuing := canRequest
  io.mem_iread_address := pcReg
  io.mem_iread_valid := canRequest

  when(issuing) {
    reqPcReg := pcReg
    busy := true.B
  }

  def decodeImm(instr: UInt): UInt = {
    val opcode = instr(6, 0)
    val immI = Cat(Fill(20, instr(31)), instr(31, 20))
    val immS = Cat(Fill(20, instr(31)), instr(31, 25), instr(11, 7))
    val immB = Cat(Fill(19, instr(31)), instr(7), instr(30, 25), instr(11, 8), 0.U(1.W))
    val immU = Cat(instr(31, 12), 0.U(12.W))
    val immJ = Cat(Fill(11, instr(31)), instr(19, 12), instr(20), instr(30, 21), 0.U(1.W))
    MuxLookup(opcode, 0.U, Seq(
      "b0010011".U -> immI, // OP-IMM
      "b0000011".U -> immI, // LOAD
      "b1100111".U -> immI, // JALR
      "b1110011".U -> immI, // SYSTEM (ecall/ebreak/csr*)
      "b0100011".U -> immS, // STORE
      "b1100011".U -> immB, // BRANCH
      "b0110111".U -> immU, // LUI
      "b0010111".U -> immU, // AUIPC
      "b1101111".U -> immJ  // JAL
    ))
  }

  q.io.enq.valid := io.mem_iout_valid && !io.clear
  val opcode = io.mem_iout_data(6, 0)
  q.io.enq.bits.instr := io.mem_iout_data
  q.io.enq.bits.pc := reqPcReg
  val rdRaw = io.mem_iout_data(11, 7)
  q.io.enq.bits.rd := MuxLookup(opcode, rdRaw, Seq(
    "b1100011".U -> 0.U, // BRANCH
    "b0100011".U -> 0.U, // STORE
    "b1110011".U -> 0.U  // SYSTEM
  ))
  q.io.enq.bits.rs1 := io.mem_iout_data(19, 15)
  q.io.enq.bits.rs2 := io.mem_iout_data(24, 20)
  q.io.enq.bits.imm := decodeImm(io.mem_iout_data)
  q.io.enq.bits.opcode := opcode
  q.io.enq.bits.funct3 := io.mem_iout_data(14, 12)
  q.io.enq.bits.funct7 := io.mem_iout_data(31, 25)

  when(io.clear) {
    q.io.deq.ready := true.B // drop queued entries
    busy := false.B
  }

  when(io.mem_iout_valid) {
    busy := false.B
  }
}
