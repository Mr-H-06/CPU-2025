import chisel3._
import chisel3.util._
import chisel3.experimental._
import chisel3.util.experimental.loadMemoryFromFile
import utils._

class Memory(initFile: String, memSize: Int, delay: Int) extends Module {
  require(delay >= 4, s"Memory delay must be >= 4, got $delay.")

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

  val instruction0 = RegNext(mem.read(Mux(io.iread.valid, io.iread.address, 0.U) + 0.U), 0.U)
  val instruction1 = RegNext(mem.read(Mux(io.iread.valid, io.iread.address, 0.U) + 1.U), 0.U)
  val instruction2 = RegNext(mem.read(Mux(io.iread.valid, io.iread.address, 0.U) + 2.U), 0.U)
  val instruction3 = RegNext(mem.read(Mux(io.iread.valid, io.iread.address, 0.U) + 3.U), 0.U)
  
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
  val memOutput = RegInit(0.U.asTypeOf(new CDBData))
  val data0 = RegNext(mem.read(memInput.bits.address + 0.U), 0.U)
  val data1 = RegNext(mem.read(memInput.bits.address + 1.U), 0.U)
  val data2 = RegNext(mem.read(memInput.bits.address + 2.U), 0.U)
  val data3 = RegNext(mem.read(memInput.bits.address + 3.U), 0.U)

  val mValidReg = RegInit(false.B)
  mValidReg := false.B

  io.memValue.ready := !memInput.valid
  io.memValue.data.valid := mValidReg
  io.memValue.data.bits := memOutput

  when (io.clear) {
    cnt.reset()
    memInput := 0.U.asTypeOf(new Valid(new MemInput))
  }.otherwise {
    when (io.memAccess.valid && !memInput.valid) {
      memInput := io.memAccess
      when (io.memAccess.bits.op === MemOpEnum.lb  || 
            io.memAccess.bits.op === MemOpEnum.lbu || 
            io.memAccess.bits.op === MemOpEnum.lh  || 
            io.memAccess.bits.op === MemOpEnum.lhu || 
            io.memAccess.bits.op === MemOpEnum.lw) {
        cnt.inc()
      }.otherwise {
        // For stores, immediately broadcast readiness on the CDB so ROB can commit
        mValidReg := true.B
        memOutput.index := io.memAccess.bits.index
        memOutput.value := 0.U
      }
    }
    when (memInput.valid) {
      when (cnt.value =/= 0.U) { // load
        assert (memInput.bits.op === MemOpEnum.lb  || 
                memInput.bits.op === MemOpEnum.lbu || 
                memInput.bits.op === MemOpEnum.lh  || 
                memInput.bits.op === MemOpEnum.lhu || 
                memInput.bits.op === MemOpEnum.lw)
        when (cnt.inc()) { // output
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
        }
      }.otherwise { // store
        assert (memInput.bits.op === MemOpEnum.sb || 
                memInput.bits.op === MemOpEnum.sh || 
                memInput.bits.op === MemOpEnum.sw )
        when (io.commit) { // output
          memInput.valid := false.B
          mValidReg := true.B
          memOutput.index := memInput.bits.index
          memOutput.value := 0.U
          switch (memInput.bits.op) {
            is (MemOpEnum.sb) {
              mem.write(memInput.bits.address, memInput.bits.value(7, 0))
            }
            is (MemOpEnum.sh) {
              mem.write(memInput.bits.address, memInput.bits.value(7, 0))
              mem.write(memInput.bits.address + 1.U, memInput.bits.value(15, 8))
            }
            is (MemOpEnum.sw) {
              mem.write(memInput.bits.address, memInput.bits.value(7, 0))
              mem.write(memInput.bits.address + 1.U, memInput.bits.value(15, 8))
              mem.write(memInput.bits.address + 2.U, memInput.bits.value(23, 16))
              mem.write(memInput.bits.address + 3.U, memInput.bits.value(31, 24))
            }
          }
        }
      }
    }
  }
}