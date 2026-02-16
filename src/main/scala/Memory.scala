import chisel3._
import chisel3.util._
import chisel3.experimental._
import chisel3.util.experimental.loadMemoryFromFile
import utils._

class Memory(initFile: String, memSize: Int, delay: Int) extends Module {
  require(delay >= 4, s"Memory delay must be >= 4, got $delay.")

  // Lightweight cycle counter for targeted debug prints
  private val dbgCycle = RegInit(0.U(32.W))
  dbgCycle := dbgCycle + 1.U

  // Helper function to convert Intel HEX format to simple hex format for loadMemoryFromFile
  private def convertIntelHexToSimpleHex(inputFile: String, outputFile: String): Unit = {
    import java.io.{BufferedReader, BufferedWriter, FileReader, FileWriter}
    
    val reader = new BufferedReader(new FileReader(inputFile))
    val writer = new BufferedWriter(new FileWriter(outputFile))
    
    try {
      var line: String = null
      var currentAddress = 0
      
      while ({ line = reader.readLine(); line != null }) {
        line = line.trim
        
        // Skip empty lines and comments
        if (line.nonEmpty && !line.startsWith("//") && !line.startsWith("#")) {
          if (line.startsWith("@")) {
            // Address line: @00000000
            val addressStr = line.substring(1)
            val newAddress = Integer.parseInt(addressStr, 16)
            
            // Pad with 00 bytes if we're jumping to a higher address
            while (currentAddress < newAddress) {
              writer.write("00")
              writer.newLine()
              currentAddress += 1
            }
            
            currentAddress = newAddress
          } else {
            // Data line: hex bytes separated by spaces
            val hexBytes = line.split("\\s+")
            
            for (hexByte <- hexBytes if hexByte.nonEmpty) {
              // Write each byte as a separate line
              writer.write(hexByte)
              writer.newLine()
              currentAddress += 1
            }
          }
        }
      }

      // Pad the remainder of the memory image with zeros so that any
      // unspecified addresses in the input file are deterministic instead of
      // picking up whatever initial value the simulator assigns.
      while (currentAddress < memSize) {
        writer.write("00")
        writer.newLine()
        currentAddress += 1
      }
    } finally {
      reader.close()
      writer.close()
    }
  }
  
  // Generate temporary file name
  private val tempFile = s"${initFile}.converted"
  
  // Convert Intel HEX to simple format if needed
  if (initFile.nonEmpty) {
    convertIntelHexToSimpleHex(initFile, tempFile)
  }
  
  val io = IO(new Bundle {
    val iread = Input(new Bundle {
      val address = UInt(32.W)
      val valid = Bool()
    })
    val iout = Output(new Bundle {
      val ready = Bool()
      val data = UInt(32.W)
      val valid = Bool()
    })
    val memAccess = Flipped(ValidIO(new MemInput))
    val memValue = Output(new Bundle {
      val ready = Bool()
      val data = Valid(new CDBData)
    })
    val clear = Input(Bool())
    val commit = Input(Bool())
  })

  val mem = SyncReadMem(memSize, UInt(8.W))
  
  if (initFile.nonEmpty) {
    loadMemoryFromFile(mem, tempFile)
  }

  val addrWidth = log2Ceil(memSize)
  def maskAddr(addr: UInt): UInt = addr(addrWidth - 1, 0)

  val iaddr = maskAddr(Mux(io.iread.valid, io.iread.address, 0.U))
  val instruction0 = RegNext(mem.read(iaddr + 0.U), 0.U)
  val instruction1 = RegNext(mem.read(iaddr + 1.U), 0.U)
  val instruction2 = RegNext(mem.read(iaddr + 2.U), 0.U)
  val instruction3 = RegNext(mem.read(iaddr + 3.U), 0.U)
  
  val iReadyReg = RegInit(true.B)
  val iValidReg = RegInit(false.B)

  io.iout.ready := iReadyReg
  io.iout.valid := iValidReg
  io.iout.data := Cat(instruction3, instruction2, instruction1, instruction0)

  when(io.clear) {
    iReadyReg := true.B
    iValidReg := false.B
  }.otherwise {
    assert(!(!iReadyReg && io.iread.valid), "Instruction memory should not be read when busy")
    iReadyReg := !io.iread.valid
    iValidReg := !iReadyReg
  }
  
  val cnt = new Counter(delay)
  val memInput = RegInit(0.U.asTypeOf(new Valid(new MemInput)))
  val storePending = RegInit(0.U.asTypeOf(new Valid(new MemInput)))
  val memOutput = RegInit(0.U.asTypeOf(new CDBData))
  val dataAddr = maskAddr(memInput.bits.address)
  val data0 = RegNext(mem.read(dataAddr + 0.U), 0.U)
  val data1 = RegNext(mem.read(dataAddr + 1.U), 0.U)
  val data2 = RegNext(mem.read(dataAddr + 2.U), 0.U)
  val data3 = RegNext(mem.read(dataAddr + 3.U), 0.U)

  val mValidReg = RegInit(false.B)
  mValidReg := false.B

  io.memValue.ready := !memInput.valid && !storePending.valid
  io.memValue.data.valid := mValidReg
  io.memValue.data.bits := memOutput

  when (io.clear) {
    cnt.reset()
    memInput := 0.U.asTypeOf(new Valid(new MemInput))
    storePending := 0.U.asTypeOf(new Valid(new MemInput))
  }.otherwise {
    val watchAddr = "h000011a0".U
    val watchAddrStack = "h0001ffa4".U
    val watchAddrStackMasked = maskAddr(watchAddrStack)
    val stackLo = "h0001f000".U
    val stackHi = "h00020000".U

    // Buffer a store if a load is in flight
    when(io.memAccess.valid && memInput.valid &&
         io.memAccess.bits.op.isOneOf(MemOpEnum.sb, MemOpEnum.sh, MemOpEnum.sw)) {
      assert(io.commit, "Store issued without commit_store")
      when(io.commit && !storePending.valid) {
        storePending.valid := true.B
        storePending.bits := io.memAccess.bits
        storePending.bits.address := maskAddr(io.memAccess.bits.address)
      }
    }

    // Drain pending store when memory is free
    when(storePending.valid && !memInput.valid) {
      val addr = storePending.bits.address
      val addrMasked = maskAddr(addr)
      memOutput.index := storePending.bits.index
      memOutput.value := 0.U
      mValidReg := true.B
      switch (storePending.bits.op) {
        is (MemOpEnum.sb) {
          mem.write(addrMasked, storePending.bits.value(7, 0))
        }
        is (MemOpEnum.sh) {
          mem.write(addrMasked, storePending.bits.value(7, 0))
          mem.write(addrMasked + 1.U, storePending.bits.value(15, 8))
        }
        is (MemOpEnum.sw) {
          mem.write(addrMasked, storePending.bits.value(7, 0))
          mem.write(addrMasked + 1.U, storePending.bits.value(15, 8))
          mem.write(addrMasked + 2.U, storePending.bits.value(23, 16))
          mem.write(addrMasked + 3.U, storePending.bits.value(31, 24))
        }
      }
      storePending.valid := false.B
    }

    // Accept a new memory request when free (no load in flight, no pending store)
    when(io.memAccess.valid && !memInput.valid && !storePending.valid) {
      val addr = io.memAccess.bits.address
      val addrMasked = maskAddr(addr)
      val isStackAddr = addr >= stackLo && addr < stackHi
      when(io.memAccess.bits.op.isOneOf(MemOpEnum.lb, MemOpEnum.lbu, MemOpEnum.lh, MemOpEnum.lhu, MemOpEnum.lw)) {
        memInput := io.memAccess
        memInput.bits.address := addrMasked
        cnt.reset()
        cnt.inc()
        when(addr === watchAddr || addr === watchAddrStack || isStackAddr) {
          printf(p"[MEM-REQ] cyc=${dbgCycle} load op=${io.memAccess.bits.op.asUInt} addr=0x${Hexadecimal(addr)} idx=${io.memAccess.bits.index}\n")
        }
      }.otherwise {
        assert(io.commit, "Store issued without commit_store")
        when(io.commit) {
          memOutput.index := io.memAccess.bits.index
          memOutput.value := 0.U
          mValidReg := true.B
          when(addr === watchAddr || addr === watchAddrStack || isStackAddr) {
            printf(p"[MEM-REQ] cyc=${dbgCycle} store op=${io.memAccess.bits.op.asUInt} addr=0x${Hexadecimal(addr)} val=0x${Hexadecimal(io.memAccess.bits.value)} idx=${io.memAccess.bits.index}\n")
          }
          switch (io.memAccess.bits.op) {
            is (MemOpEnum.sb) {
              mem.write(addrMasked, io.memAccess.bits.value(7, 0))
            }
            is (MemOpEnum.sh) {
              mem.write(addrMasked, io.memAccess.bits.value(7, 0))
              mem.write(addrMasked + 1.U, io.memAccess.bits.value(15, 8))
            }
            is (MemOpEnum.sw) {
              mem.write(addrMasked, io.memAccess.bits.value(7, 0))
              mem.write(addrMasked + 1.U, io.memAccess.bits.value(15, 8))
              mem.write(addrMasked + 2.U, io.memAccess.bits.value(23, 16))
              mem.write(addrMasked + 3.U, io.memAccess.bits.value(31, 24))
            }
          }
        }
      }
    }

    // Produce load response after delay
    when(memInput.valid) {
      when(cnt.value =/= 0.U) {
        when(cnt.inc()) {
          memInput.valid := false.B
          mValidReg := true.B
          memOutput.index := memInput.bits.index
          switch (memInput.bits.op) {
            is (MemOpEnum.lb) {
              memOutput.value := Cat(Fill(24, data0(7)), data0)
            }
            is (MemOpEnum.lbu) {
              memOutput.value := Cat(Fill(24, 0.B), data0)
            }
            is (MemOpEnum.lh) {
              memOutput.value := Cat(Fill(16, data1(7)), data1, data0)
            }
            is (MemOpEnum.lhu) {
              memOutput.value := Cat(Fill(16, 0.B), data1, data0)
            }
            is (MemOpEnum.lw) {
              memOutput.value := Cat(data3, data2, data1, data0)
            }
          }
          when(memInput.bits.address === watchAddr || memInput.bits.address === watchAddrStackMasked || (memInput.bits.address >= maskAddr(stackLo) && memInput.bits.address < maskAddr(stackHi))) {
            printf(p"[MEM-RSP] cyc=${dbgCycle} load addr=0x${Hexadecimal(memInput.bits.address)} val=0x${Hexadecimal(memOutput.value)} idx=${memOutput.index}\n")
          }
        }
      }
    }
  }
}