package mdio

import chisel3._
import chisel3.util._
import chisel3.experimental._
import chisel3.Driver

// Wishbone slave interface
class WbSlave (private val dwidth: Int,
               private val awidth: Int) extends Bundle {
  val adr_i = Input(UInt(awidth.W))
  val dat_i = Input(UInt(dwidth.W))
  val dat_o = Output(UInt(dwidth.W))
  val we_i = Input(Bool())
  val stb_i = Input(Bool())
  val ack_o = Output(Bool())
  val cyc_i = Input(Bool())
}

class MdioWb(val mainFreq: Int,
             val targetFreq: Int) extends Module {
  val io = IO(new Bundle {
    val mdio = new MdioIf()
    val wbs = new WbSlave(16, 2)
  })

  val dataSize = 16

  // plug mdio module
  val mdio = Module(new Mdio(mainFreq, targetFreq))
  mdio.io.mdio <> io.mdio

/** register (16bits)
 *  0x0 : status
 *  0x1 : control
 *  0x2 : readData
 *  0x3 : writeData
 */
  val STATUSADDR = 0.U
  val CONTROLADDR = 1.U
  val READDATAADDR = 2.U
  val WRITEDATAADDR = 3.U
  val status = Wire(UInt(dataSize.W))
  val control = Wire(UInt(dataSize.W))
  val readData = RegInit(0.U(dataSize.W))
  val writeData = RegInit(0.U(dataSize.W))

/**
 * status (R):
 *  | 15 .. 1 |  0 |
 *  |---------|----|
 *  |  void   |busy| 
 *  |---------|----|
 */
  val busyReg = RegInit(true.B)
  status := "h00".U(15.W) ## busyReg


/**
 * control (W):
 *  | 15 |      | 7..5 | 4..0 |
 *  |----|------|------|------|
 *  | R  |      | aphy | areg |
 *  |----|------|------|------|
 */
  val aPhyReg = RegInit(0.U(3.W))
  val aRegReg = RegInit(0.U(5.W))
  val readBitReg = RegInit(false.B)
  control := readBitReg ## "h00".U(5.W) ## "b00".U(2.W) ## aPhyReg ## aRegReg

/**
 * readData (R):
 *  |  15 .. 0 |
 *  |----------|
 *  | readData |
 *  |----------|
 *
 * writeData (W):
 *  |  15 .. 0  |
 *  |-----------|
 *  | writeData |
 *  |-----------|
 *
 */

  val readFrameStart = RegInit(false.B)
  val writeFrameStart = RegInit(false.B)

  // Wishbone state machine
  val swbinit::swbread::swbwrite::Nil = Enum(3)
  val wbSm = RegInit(swbinit)
  val ackReg = RegInit(false.B)
  val wbReadReg = RegInit(0.U(dataSize.W))

  ackReg := false.B

  switch(wbSm){
    is(swbinit){
      when(io.wbs.stb_i & io.wbs.cyc_i){
        when(io.wbs.we_i){
          wbSm := swbwrite
        }.otherwise {
          wbSm := swbread
        }
      }
    }
    is(swbread){
      wbSm := swbinit
    }
    is(swbwrite){
      wbSm := swbinit
    }
  }

  ackReg := (wbSm === swbread) || (wbSm === swbwrite)

  // read registers
  when(wbSm === swbread){
    switch(io.wbs.adr_i){
      is(STATUSADDR){
        wbReadReg := status 
      }
      is(CONTROLADDR){
        wbReadReg := control
      }
      is(READDATAADDR){
        wbReadReg := readData
      }
      is(WRITEDATAADDR){
        wbReadReg := writeData
      }
    }
  }

  // write registers
  readFrameStart := false.B
  writeFrameStart := false.B
  when(wbSm === swbwrite){
    switch(io.wbs.adr_i) {
      is(CONTROLADDR){
        control := io.wbs.dat_i
        when(io.wbs.dat_i(15) === true.B){
          readFrameStart := true.B
        }
      }
      is(WRITEDATAADDR){
        writeData := io.wbs.dat_i
        writeFrameStart := true.B
      }
    }
  }

  io.wbs.dat_o := wbReadReg
  io.wbs.ack_o := ackReg

  // Mdio decoupled control state machine
  //   0         1         2         3         4
  val sminit::smread::smreadwait::smwrite::smwritewait::Nil = Enum(5)
  var mdioStateReg = RegInit(sminit)

  mdio.io.phyreg.bits := aPhyReg ## aRegReg
  mdio.io.phyreg.valid := false.B
  mdio.io.data_i.bits := writeData
  mdio.io.data_i.valid := false.B
  mdio.io.data_o.ready := false.B
  switch(mdioStateReg){
    is(sminit){
        when(readFrameStart){
          when(mdio.io.phyreg.ready){
            mdioStateReg := smread
          }
        }
        when(writeFrameStart){
          when(mdio.io.phyreg.ready && mdio.io.data_i.ready){
            mdioStateReg := smwrite
          }
        }
    }
    is(smread){
      mdio.io.phyreg.valid := true.B
      mdio.io.data_o.ready := true.B
      mdioStateReg := smreadwait
    }
    is(smreadwait){
      mdio.io.data_o.ready := true.B
      when(mdio.io.data_o.valid) {
        readData := mdio.io.data_o.bits
        mdioStateReg := sminit
      }
    }
    is(smwrite){
      mdio.io.phyreg.valid := true.B
      mdio.io.data_i.valid := true.B
      mdioStateReg := smwritewait
    }
    is(smwritewait){
      when(mdio.io.data_i.ready){
        mdioStateReg := sminit
      }
    }
  }
  busyReg := (mdioStateReg =/= sminit)
}