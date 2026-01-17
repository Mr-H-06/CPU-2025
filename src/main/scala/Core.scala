import chisel3._
import chisel3.util._
import utils._

class Core(initFile: String = "", memSize: Int = 4096, memDelay: Int = 4, startPC: Int = 0) extends Module {
  val io = IO(new Bundle {
    val halted = Output(Bool())
    val debug_cdb_rob = Output(Valid(new CDBData))
    val debug_reg_a0 = Output(UInt(32.W))
    val debug_pc = Output(UInt(32.W))
  })

  // Modules
  val ifu = Module(new InstructionFetch(initialPC = startPC))
  val rob = Module(new ReorderBuffer())
  val rf = Module(new RegisterFile())
  val rs = Module(new ReservationStations())
  val lsb = Module(new ReservationStationLSB())
  val alu = Module(new ALU())
  val cdb = Module(new CommonDataBus())
  val mem = Module(new Memory(initFile, memSize, memDelay))

  // Debug cycle counter
  val dbgCycle = RegInit(0.U(32.W))
  dbgCycle := dbgCycle + 1.U

  // Watchdog removed

  val globalClear = reset.asBool || rob.io.clear

  // Instruction Fetch connections
  val robResetHasTarget = rob.io.pc_reset.orR
  ifu.io.clear := globalClear
  ifu.io.resetValid := rob.io.clear && robResetHasTarget
  ifu.io.resetPC := rob.io.pc_reset

  // Memory instruction side
  mem.io.clear := globalClear
  mem.io.commit := rob.io.commit_store
  mem.io.iread.address := ifu.io.mem_iread_address
  mem.io.iread.valid := ifu.io.mem_iread_valid
  ifu.io.mem_iout_ready := mem.io.iout.ready
  ifu.io.mem_iout_valid := mem.io.iout.valid
  ifu.io.mem_iout_data := mem.io.iout.data

  // Common Data Bus
  cdb.io.clear := globalClear
  cdb.io.lsb.valid := mem.io.memValue.data.valid
  cdb.io.lsb.bits := mem.io.memValue.data.bits
  cdb.io.alu.valid := alu.io.result_valid
  cdb.io.alu.bits.index := alu.io.result_bits.tag
  cdb.io.alu.bits.value := alu.io.result_bits.value
  io.debug_cdb_rob := cdb.io.rob

  // Register File wiring
  rf.io.writeback_valid := rob.io.writeback_valid
  rf.io.writeback_index := rob.io.writeback_index
  rf.io.writeback_tag := rob.io.writeback_tag
  rf.io.writeback_value := rob.io.writeback_value
  rf.io.tail := rob.io.tail
  rf.io.destination_valid := false.B
  rf.io.destination := 0.U
  rf.io.clear := rob.io.clear

  // Reservation Stations (ALU)
  rs.io.clear := globalClear
  rs.io.cdb := cdb.io.rs
  rs.io.rob_values := rob.io.values
  rs.io.rf_regs := rf.io.alu_regs
  rs.io.fu_ready := alu.io.ready

  // ALU
  alu.io.clear := globalClear
  alu.io.exec_valid := rs.io.exec_valid
  alu.io.exec_bits := rs.io.exec_bits
  alu.io.CDB_ready := true.B

  // LSB
  lsb.io.clear := globalClear
  lsb.io.cdb := cdb.io.rs
  lsb.io.rf_entries := rf.io.lsb_regs
  lsb.io.rob_entries := rob.io.values
  lsb.io.wb_valid := rob.io.writeback_valid
  lsb.io.wb_index := rob.io.writeback_index
  lsb.io.wb_tag := rob.io.writeback_tag
  lsb.io.wb_value := rob.io.writeback_value
  lsb.io.mem_ready := mem.io.memValue.ready

  // Memory data side (from LSB)
  mem.io.memAccess.valid := lsb.io.exec_valid
  mem.io.memAccess.bits.op := lsb.io.exec_bits.op
  mem.io.memAccess.bits.value := lsb.io.exec_bits.value
  mem.io.memAccess.bits.address := lsb.io.exec_bits.address
  mem.io.memAccess.bits.index := lsb.io.exec_bits.index

  // ROB
  rob.io.cdb := cdb.io.rob
  rob.io.issue_valid := false.B
  rob.io.issue_has_value := false.B
  rob.io.issue_value := 0.U
  rob.io.issue_bits := 0.U.asTypeOf(new ROBIssueBits())

  // Decode
  val instrValid = ifu.io.out.valid
  // Do not accept/issue new instructions while a global clear is active
  val canIssue = instrValid && !globalClear
  val instr = ifu.io.out.bits
  val opcode = instr.opcode
  val funct3 = instr.funct3
  val funct7 = instr.funct7
  val rs1 = instr.rs1
  val rs2 = instr.rs2
  val rd = instr.rd
  val imm = instr.imm
  val pc = instr.pc

  // Defaults
  rs.io.issue_valid := false.B
  rs.io.issue_bits := 0.U.asTypeOf(new RSIssueBits())
  lsb.io.issue_valid := false.B
  lsb.io.issue_bits := 0.U.asTypeOf(new IssueBitsLSB())

  val issueReadyALU = rs.io.issue_ready && rob.io.ready
  val issueReadyLSB = lsb.io.issue_ready && rob.io.ready
  val issueReadySimple = rob.io.ready

  val willFire = WireDefault(false.B)
  val robPrediction = WireDefault(0.U(32.W))
  val robPcReset = WireDefault(0.U(32.W))
  val robHasValue = WireDefault(false.B)
  val robValue = WireDefault(0.U(32.W))
  val writeDest = WireDefault(false.B)

  // ALU op mapping helpers
  def aluOpImm(f3: UInt, f7: UInt): AluOpEnum.Type = {
    MuxLookup(f3, AluOpEnum.ADD, Seq(
      "b000".U -> AluOpEnum.ADD, // ADDI
      "b010".U -> AluOpEnum.SLT, // SLTI
      "b011".U -> AluOpEnum.SLTU, // SLTIU
      "b100".U -> AluOpEnum.XOR, // XORI
      "b110".U -> AluOpEnum.OR,  // ORI
      "b111".U -> AluOpEnum.AND, // ANDI
      "b001".U -> AluOpEnum.LL,  // SLLI
      "b101".U -> Mux(f7(5), AluOpEnum.RA, AluOpEnum.RL) // SRAI/SRLI
    ))
  }

  def aluOpR(f3: UInt, f7: UInt): AluOpEnum.Type = {
    MuxLookup(f3, AluOpEnum.ADD, Seq(
      "b000".U -> Mux(f7(5), AluOpEnum.SUB, AluOpEnum.ADD), // ADD/SUB
      "b001".U -> AluOpEnum.LL, // SLL
      "b010".U -> AluOpEnum.SLT, // SLT
      "b011".U -> AluOpEnum.SLTU, // SLTU
      "b100".U -> AluOpEnum.XOR, // XOR
      "b101".U -> Mux(f7(5), AluOpEnum.RA, AluOpEnum.RL), // SRA/SRL
      "b110".U -> AluOpEnum.OR,  // OR
      "b111".U -> AluOpEnum.AND  // AND
    ))
  }

  // Issue logic by opcode
  switch(opcode) {
    is("b0110111".U) { // LUI
      willFire := canIssue && issueReadySimple
      robHasValue := true.B
      robValue := imm
      writeDest := rd =/= 0.U
    }
    is("b0010111".U) { // AUIPC
      willFire := canIssue && issueReadySimple
      robHasValue := true.B
      robValue := pc + imm
      writeDest := rd =/= 0.U
    }
    is("b1101111".U) { // JAL
      willFire := canIssue && issueReadySimple
      robHasValue := true.B
      robValue := pc + 4.U
      robPcReset := pc + imm
      robPrediction := 0.U
      writeDest := rd =/= 0.U
    }
    is("b1100111".U) { // JALR
      willFire := canIssue && issueReadyALU
      rs.io.issue_valid := willFire
      rs.io.issue_bits.op := AluOpEnum.ADD
      rs.io.issue_bits.op1_index := rs1
      rs.io.issue_bits.op2_index := 0.U
      rs.io.issue_bits.op2_value := imm
      rs.io.issue_bits.op2_type := true.B
      rs.io.issue_bits.dest_tag := rob.io.tail
      robHasValue := true.B
      robValue := pc + 4.U
      robPrediction := 0.U
      writeDest := rd =/= 0.U
    }
    is("b1100011".U) { // Branches
      willFire := canIssue && issueReadyALU
      rs.io.issue_valid := willFire
      rs.io.issue_bits.op1_index := rs1
      rs.io.issue_bits.op2_index := rs2
      rs.io.issue_bits.op2_value := 0.U
      rs.io.issue_bits.op2_type := false.B
      rs.io.issue_bits.dest_tag := rob.io.tail
      robPcReset := pc + imm
      robPrediction := 0.U // predict not taken
      switch(funct3) {
        is("b000".U) { rs.io.issue_bits.op := AluOpEnum.EQ }  // BEQ
        is("b001".U) { rs.io.issue_bits.op := AluOpEnum.NE }  // BNE
        is("b100".U) { rs.io.issue_bits.op := AluOpEnum.SLT } // BLT
        is("b101".U) { rs.io.issue_bits.op := AluOpEnum.GE }  // BGE
        is("b110".U) { rs.io.issue_bits.op := AluOpEnum.SLTU } // BLTU
        is("b111".U) { rs.io.issue_bits.op := AluOpEnum.GEU } // BGEU
        // default already set
      }
    }
    is("b0000011".U) { // LOAD
      willFire := canIssue && issueReadyLSB
      lsb.io.issue_valid := willFire
      lsb.io.issue_bits.dest_tag := rob.io.tail
      lsb.io.issue_bits.op1_index := rs1
      lsb.io.issue_bits.op2_index := rs1
      lsb.io.issue_bits.op3_value := imm
      robPrediction := 0.U
      writeDest := rd =/= 0.U
      lsb.io.issue_bits.op := MuxLookup(funct3, MemOpEnum.lw, Seq(
        "b000".U -> MemOpEnum.lb,
        "b001".U -> MemOpEnum.lh,
        "b010".U -> MemOpEnum.lw,
        "b100".U -> MemOpEnum.lbu,
        "b101".U -> MemOpEnum.lhu
      ))
    }
    is("b0100011".U) { // STORE
      willFire := canIssue && issueReadyLSB
      lsb.io.issue_valid := willFire
      lsb.io.issue_bits.dest_tag := rob.io.tail
      lsb.io.issue_bits.op1_index := rs2 // store value
      lsb.io.issue_bits.op2_index := rs1 // base
      lsb.io.issue_bits.op3_value := imm
      lsb.io.issue_bits.op := MuxLookup(funct3, MemOpEnum.sw, Seq(
        "b000".U -> MemOpEnum.sb,
        "b001".U -> MemOpEnum.sh,
        "b010".U -> MemOpEnum.sw
      ))
    }
    is("b0010011".U) { // OP-IMM
      willFire := canIssue && issueReadyALU
      rs.io.issue_valid := willFire
      rs.io.issue_bits.op := aluOpImm(funct3, funct7)
      rs.io.issue_bits.op1_index := rs1
      rs.io.issue_bits.op2_index := 0.U
      rs.io.issue_bits.op2_value := imm
      rs.io.issue_bits.op2_type := true.B
      rs.io.issue_bits.dest_tag := rob.io.tail
      writeDest := rd =/= 0.U
    }
    is("b0110011".U) { // OP
      willFire := canIssue && issueReadyALU
      rs.io.issue_valid := willFire
      rs.io.issue_bits.op := aluOpR(funct3, funct7)
      rs.io.issue_bits.op1_index := rs1
      rs.io.issue_bits.op2_index := rs2
      rs.io.issue_bits.op2_value := 0.U
      rs.io.issue_bits.op2_type := false.B
      rs.io.issue_bits.dest_tag := rob.io.tail
      writeDest := rd =/= 0.U
    }
    is("b0001111".U) { // FENCE -> NOP
      willFire := canIssue && issueReadySimple
      robHasValue := true.B
      robValue := 0.U
    }
    is("b1110011".U) { // SYSTEM -> treat as NOP
      willFire := canIssue && issueReadySimple
      robHasValue := true.B
      robValue := 0.U
    }
  }

  // Early debug: see why issue may stall
  when(dbgCycle < 256.U && instrValid) {
    when(willFire) {
      printf("[CORE] issue cycle=%d pc=%x opcode=%b rd=%d rs1=%d rs2=%d imm=%x readyALU=%d readyLSB=%d robReady=%d\n",
        dbgCycle, pc, opcode, rd, rs1, rs2, imm, issueReadyALU, issueReadyLSB, rob.io.ready)
    }.otherwise {
      printf("[CORE] stall cycle=%d pc=%x opcode=%b rd=%d rs1=%d rs2=%d imm=%x readyALU=%d readyLSB=%d robReady=%d instrValid=%d\n",
        dbgCycle, pc, opcode, rd, rs1, rs2, imm, issueReadyALU, issueReadyLSB, rob.io.ready, instrValid)
    }
  }

  // Issue to ROB
  rob.io.issue_valid := willFire
  rob.io.issue_has_value := robHasValue
  rob.io.issue_value := robValue
  rob.io.issue_bits.op := opcode
  rob.io.issue_bits.rd := rd
  rob.io.issue_bits.pc := pc
  rob.io.issue_bits.prediction := robPrediction
  rob.io.issue_bits.pc_reset := robPcReset

  // Register file bookkeeping
  rf.io.destination_valid := willFire && writeDest
  rf.io.destination := rd

  io.debug_reg_a0 := rf.io.alu_regs(10).value
  io.debug_pc := ifu.io.mem_iread_address

  // IF readiness
  ifu.io.out.ready := willFire

  // Halt detection: sb x0, -1(x0) with byte 0
  val haltReg = RegInit(false.B)
  when(lsb.io.exec_valid && lsb.io.exec_bits.op === MemOpEnum.sb &&
       lsb.io.exec_bits.address === "hFFFF_FFFF".U && (lsb.io.exec_bits.value & 0xFF.U) === 0.U) {
    haltReg := true.B
  }
  io.halted := haltReg
}
