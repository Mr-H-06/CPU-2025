import chisel3._
import chisel3.util._
import chisel3.experimental._
import chisel3.util.experimental.loadMemoryFromFile

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

class Memory(initFile: String, memSize: Int, delay: Int) extends Module {
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
    val memAccess = Input(new Bundle {
      val op = MemOpEnum()
      val value = UInt(32.W)
      val address = UInt(32.W)
      val valid = Bool()
    })
    val memValue = Output(new Bundle {
      val ready = Bool()
      val data = UInt(32.W)
      val valid = Bool()
    })
    val clear = Input(Bool())
  })

  val mem = SyncReadMem(memSize, UInt(8.W))
  
  if (initFile.nonEmpty) {
    loadMemoryFromFile(mem, tempFile)
  }

  val iread_ready = RegInit(true.B)
  val instruction0 = ShiftRegister(mem.read(io.iread.address + 0.U), delay - 1)
  val instruction1 = ShiftRegister(mem.read(io.iread.address + 1.U), delay - 1)
  val instruction2 = ShiftRegister(mem.read(io.iread.address + 2.U), delay - 1)
  val instruction3 = ShiftRegister(mem.read(io.iread.address + 3.U), delay - 1)
  
  io.iout.ready := iread_ready
  io.iout.valid := ShiftRegister(io.iread.valid, delay, 0.U, io.clear)
  io.iout.data := Cat(instruction3, instruction2, instruction1, instruction0)

  when(io.iread.valid) {
    iread_ready <= false.B
  }
  when(io.iout.valid) {
    iread_ready <= true.B
  }
  
  val loadStoreReady = RegInit(true.B)
  val load0 = ShiftRegister(mem.read(io.memAccess.address + 0.U), delay - 1)
  val load1 = ShiftRegister(mem.read(io.memAccess.address + 1.U), delay - 1)
  val load2 = ShiftRegister(mem.read(io.memAccess.address + 2.U), delay - 1)
  val load3 = ShiftRegister(mem.read(io.memAccess.address + 3.U), delay - 1)
  
  io.memValue.ready := loadStoreReady
  io.memValue.valid := ShiftRegister(io.memAccess.valid, delay, 0.U, io.clear)
  
  switch(io.memAccess.op) {
    
  }

  when(io.memValue.valid) {
    loadStoreReady <= false.B
  }
  when(io.iout.valid) {
    loadStoreReady <= true.B
  }

  when(io.clear) {
    iread_ready <= true.B
    loadStoreReady <= true.B
  }
}