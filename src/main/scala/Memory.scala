import chisel3._
import chisel3.util._
import chisel3.experimental._
import chisel3.util.experimental.loadMemoryFromFile
import utils._

class Memory(initFile: String, memSize: Int) extends Module {
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
      val index = UInt(5.W)
      val valid = Bool()
    })
    val memValue = Output(new Bundle {
      val ready = Bool()
      val data = ValidIO(new CDBData)
    })
    val clear = Input(Bool())
  })

  val mem = SyncReadMem(memSize, UInt(8.W))
  
  if (initFile.nonEmpty) {
    loadMemoryFromFile(mem, tempFile)
  }

  val instruction0 = RegNext(mem.read(Mux(io.iread.valid, io.iread.address, 0.U) + 0.U), 0.U)
  val instruction1 = RegNext(mem.read(Mux(io.iread.valid, io.iread.address, 0.U) + 1.U), 0.U)
  val instruction2 = RegNext(mem.read(Mux(io.iread.valid, io.iread.address, 0.U) + 2.U), 0.U)
  val instruction3 = RegNext(mem.read(Mux(io.iread.valid, io.iread.address, 0.U) + 3.U), 0.U)
  
  io.iout.ready := true.B
  io.iout.valid := RegNext(io.iread.valid)
  io.iout.data := Cat(instruction3, instruction2, instruction1, instruction0)
}