package Common

import chisel3._
import chisel3.util._
import Common._
import Util._
import Constants._
import freechips.rocketchip.config._

object DMConsts{

  def dmiDataSize = 32
  def nDMIAddrSize = 7
  def dmiOpSize = 2
  def dmi_OP_NONE            = "b00".U
  def dmi_OP_READ            = "b01".U
  def dmi_OP_WRITE           = "b10".U

  def dmiRespSize = 2
  def dmi_RESP_SUCCESS     = "b00".U
  def dmi_RESP_FAILURE     = "b01".U
  def dmi_RESP_HW_FAILURE  = "b10".U
  // This is used outside this block
  // to indicate 'busy'.
  def dmi_RESP_RESERVED    = "b11".U

  def dmi_haltStatusAddr   = 0x40
  def nProgBuf = 4
  def nDataCount = 1
  def hartInfo = "h111bc0".U
}


class DMIReq(addrBits : Int) extends Bundle {
  val op   = Output(UInt(DMConsts.dmiOpSize.W))
  val addr = Output(UInt(addrBits.W))
  val data = Output(UInt(DMConsts.dmiDataSize.W))

  override def cloneType = new DMIReq(addrBits).asInstanceOf[this.type]
}

/** Structure to define the contents of a Debug Bus Response
  */
class DMIResp() extends Bundle {
  val data = Output(UInt(DMConsts.dmiDataSize.W))
  val resp = Output(UInt(DMConsts.dmiRespSize.W))
}

/** Structure to define the top-level DMI interface 
  *  of DebugModule.
  *  DebugModule is the consumer of this interface.
  *  Therefore it has the 'flipped' version of this.
  */
class DMIIO(implicit p: Parameters) extends Bundle {
  val req = new  DecoupledIO(new DMIReq(DMConsts.nDMIAddrSize))
  val resp = Flipped(new DecoupledIO(new DMIResp))
}

/** This includes the clock and reset as these are passed through the
  *  hierarchy until the Debug Module is actually instantiated. 
  *  
  */

class SimDTM(implicit p: Parameters) extends BlackBox {
  val io = IO(new Bundle {
      val clk = Input(Clock())
      val reset = Input(Bool())
      val debug = new DMIIO()
      val exit = Output(UInt(32.W))
    })
  
  def connect(tbclk: Clock, tbreset: Bool, dutio: DMIIO, tbsuccess: Bool) = {
    io.clk := tbclk
    io.reset := tbreset
    dutio <> io.debug 

    tbsuccess := io.exit === 1.U
    when (io.exit >= 2.U) {
      printf("*** FAILED *** (exit code = %d)\n", io.exit >> 1.U)
      //stop(1)
    }
  }
}

class DebugDPath(implicit p: Parameters) extends Bundle
{
  // REG access
  val addr = Output(UInt(5.W))
  val wdata = Output(UInt(32.W))
  val validreq = Output(Bool())
  val rdata = Input(UInt(32.W))
  val resetpc = Output(Bool())
}

class DebugCPath(implicit p: Parameters) extends Bundle
{
  val halt = Output(Bool())
}

class DebugIo(implicit p: Parameters) extends Bundle
{
  val dmi = Flipped(new DMIIO())
  val ddpath = new DebugDPath()
  val dcpath = new DebugCPath()
  val debugmem = new MemPortIo(data_width = 32)
  val resetcore = Output(Bool())
}

class DebugModule(implicit p: Parameters) extends Module {
  val io = IO(new DebugIo())
  val xlen = p(xprlen)
  // Following initializations are incase no "when" conditions are true
  io.debugmem.req.bits := new MemReq(p(xprlen)).fromBits(0.U)
  io.debugmem.req.bits.typ := MT_W
  io.debugmem.req.valid := false.B
  io.dmi.resp.bits.resp := DMConsts.dmi_RESP_SUCCESS
  io.debugmem.req.bits.fcn := 0.U
  io.dmi.req.ready := true.B
  
  val dmstatusReset  = Wire(new DMSTATUSFields())
  dmstatusReset := (new DMSTATUSFields()).fromBits(0.U)
  dmstatusReset.authenticated := true.B
  dmstatusReset.versionlo := "b10".U
  val dmstatus = Reg(init = dmstatusReset)
  val sbcsreset = Wire(new SBCSFields())
  sbcsreset := (new SBCSFields()).fromBits(0.U)
  sbcsreset.sbaccess := 2.U
  sbcsreset.sbasize := 32.U
  sbcsreset.sbaccess32 := true.B
  sbcsreset.sbaccess16 := false.B
  sbcsreset.sbaccess8 := false.B
  val sbcs = Reg(init = sbcsreset)
  val abstractcsReset = Wire(new ABSTRACTCSFields())
  abstractcsReset := (new ABSTRACTCSFields()).fromBits(0.U)
  abstractcsReset.datacount := DMConsts.nDataCount.U
  abstractcsReset.progsize := DMConsts.nProgBuf.U
  val addr = RegEnable(io.dmi.req.bits.addr, io.dmi.req.fire()) // DMI Address
  val op = RegEnable(io.dmi.req.bits.op, io.dmi.req.fire()) // DMI Op
  val wdata = RegEnable(io.dmi.req.bits.data, io.dmi.req.fire()) // DMI Data
  val abstractcs = Reg(init = abstractcsReset)
  val command = Reg(new ACCESS_REGISTERFields())
  val dmcontrol = Reg(new DMCONTROLFields())
  val data0 = Reg(UInt(xlen.W))  //arg0
  val data1 = Reg(UInt(xlen.W))  //arg1
  val data2 = Reg(UInt(xlen.W))  //arg2
  val sbaddr = Reg(UInt(xlen.W))
  val sbdata = Reg(UInt(xlen.W))
  val resetcore = Reg(init = true.B)

  val read_map = collection.mutable.LinkedHashMap[Int,UInt](
    DMI_RegAddrs.DMI_ABSTRACTCS -> abstractcs.asUInt,
    DMI_RegAddrs.DMI_DMCONTROL -> dmcontrol.asUInt, 
    DMI_RegAddrs.DMI_DMSTATUS  -> dmstatus.asUInt,
    DMI_RegAddrs.DMI_COMMAND -> command.asUInt,
    DMI_RegAddrs.DMI_HARTINFO -> DMConsts.hartInfo,
    DMI_RegAddrs.DMI_ABSTRACTAUTO -> 0.U,
    DMI_RegAddrs.DMI_CFGSTRADDR0 -> 0.U,
    DMI_RegAddrs.DMI_DATA0 -> data0,
    (DMI_RegAddrs.DMI_DATA0 + 1) -> data1,
    (DMI_RegAddrs.DMI_DATA0 + 2) -> data2,
    DMI_RegAddrs.DMI_AUTHDATA -> 0.U,
    DMI_RegAddrs.DMI_SERCS -> 0.U,
    DMI_RegAddrs.DMI_SBCS -> sbcs.asUInt,
    DMI_RegAddrs.DMI_SBADDRESS0 -> sbaddr,
    DMI_RegAddrs.DMI_SBDATA0 -> sbdata)
  val decoded_addr = read_map map { case (k, v) => k -> (addr === k) }
  val reqval = Reg(init = false.B)
  val dwreqval = Reg(init = false.B)
  val memongoing = Reg(init = false.B)
  val firstreaddone = Reg(init = false.B)
  io.dmi.resp.bits.data := Mux1H(for ((k, v) <- read_map) yield decoded_addr(k) -> v)
  dmstatus.allhalted := dmcontrol.haltreq
  dmstatus.allrunning := dmcontrol.resumereq 
  io.dcpath.halt := dmstatus.allhalted && !dmstatus.allrunning
  when ((op === DMConsts.dmi_OP_WRITE) && io.dmi.resp.fire()){ 
    when(decoded_addr(DMI_RegAddrs.DMI_ABSTRACTCS)) { 
      val tempabstractcs = new ABSTRACTCSFields().fromBits(wdata)
      abstractcs.cmderr := tempabstractcs.cmderr 
    }
    when(decoded_addr(DMI_RegAddrs.DMI_COMMAND)) { 
      val tempcommand = new ACCESS_REGISTERFields().fromBits(wdata)
      when(tempcommand.size === 2.U){
        command.postexec := tempcommand.postexec
        command.regno := tempcommand.regno
        command.transfer := tempcommand.transfer
        command.write := tempcommand.write
        abstractcs.cmderr := 1.U
      } .otherwise {
        abstractcs.cmderr := 2.U
      }
    }
    when(decoded_addr(DMI_RegAddrs.DMI_DMCONTROL)) { 
      val tempcontrol = new DMCONTROLFields().fromBits(wdata)
      dmcontrol.haltreq := tempcontrol.haltreq
      dmcontrol.resumereq := tempcontrol.resumereq 
      dmcontrol.hartreset := tempcontrol.hartreset
      dmcontrol.ndmreset := tempcontrol.ndmreset
      dmcontrol.dmactive := tempcontrol.dmactive
      // Support only for single core sodor
      when(tempcontrol.hartsel =/= 0.U){
        dmstatus.anynonexistent := true.B
      } .otherwise {
        dmstatus.anynonexistent := false.B
      }
    }
    when(decoded_addr(DMI_RegAddrs.DMI_SBCS)){
      val tempsbcs = new SBCSFields().fromBits(wdata)
      sbcs.sbsingleread := tempsbcs.sbsingleread
      sbcs.sbaccess := tempsbcs.sbaccess
      sbcs.sbautoincrement := tempsbcs.sbautoincrement
      sbcs.sbautoread := tempsbcs.sbautoread
      sbcs.sberror := tempsbcs.sberror
    }
    when(decoded_addr(DMI_RegAddrs.DMI_SBADDRESS0)) { 
      sbaddr := wdata
    }
    when(decoded_addr(DMI_RegAddrs.DMI_DATA0)) { data0 := wdata }
    when(decoded_addr(DMI_RegAddrs.DMI_DATA0+1)) { data1 := wdata }
    when(decoded_addr(DMI_RegAddrs.DMI_DATA0+2)) { data2 := wdata }
  }

  io.debugmem.resp.ready := true.B 
  val waitrespack = Reg(Bool()) // Hold Valid Signal till resp is ACK'ed
  waitrespack := io.dmi.resp.valid && !io.dmi.resp.ready 
  when(decoded_addr(DMI_RegAddrs.DMI_SBDATA0)) {
    io.dmi.resp.valid := RegNext(io.debugmem.resp.valid) || waitrespack
  } .otherwise {
    io.dmi.resp.valid := RegNext(io.dmi.req.fire()) || waitrespack
  }

  when(io.debugmem.req.fire()){
    memongoing := true.B
  }
  when(io.debugmem.resp.fire()){
    memongoing := false.B
    op := DMConsts.dmi_OP_NONE
  }

  when (decoded_addr(DMI_RegAddrs.DMI_SBDATA0) && (op === DMConsts.dmi_OP_WRITE)) {
    io.debugmem.req.bits.addr := sbaddr
    io.debugmem.req.bits.data := wdata
    io.debugmem.req.bits.fcn :=  M_XWR
    io.debugmem.req.valid := !memongoing
    when (sbcs.sbautoincrement && io.debugmem.resp.fire()) {  sbaddr := sbaddr + 4.U  }
  }

  when (decoded_addr(DMI_RegAddrs.DMI_SBDATA0) && (op === DMConsts.dmi_OP_READ)) {
    io.debugmem.req.bits.addr :=  sbaddr
    io.debugmem.req.bits.fcn := M_XRD
    io.debugmem.req.valid := !memongoing
    // for async data readily available
    // so capture it in reg
    when (sbcs.sbautoincrement && io.debugmem.resp.fire()) {
      sbdata := io.debugmem.resp.bits.data
      sbaddr := sbaddr + 4.U
    }
  }



  io.resetcore := resetcore
  // Following are two non-standard address used 
  // 0x44 pull it out of reset
  // 0x48 put it into reset
  when (io.dmi.req.bits.addr === "h44".U && io.dmi.req.valid) {
    resetcore := false.B
    dmstatus.allhalted := false.B
    dmstatus.anyhalted := false.B
    dmstatus.allrunning := true.B
    dmstatus.anyrunning := true.B
    dmstatus.allresumeack := true.B
    dmstatus.anyresumeack := true.B
  } .elsewhen (io.dmi.req.bits.addr === "h48".U && io.dmi.req.valid) {
    resetcore := true.B
    dmstatus.allhalted := true.B
    dmstatus.anyhalted := true.B
    dmstatus.allrunning := false.B
    dmstatus.anyrunning := false.B
    dmstatus.allresumeack := false.B
    dmstatus.anyresumeack := false.B
  }

  /// abstract cs command regfile access
  io.ddpath.resetpc := 0.U
  io.ddpath.wdata := 0.U
  io.ddpath.validreq := false.B
  io.ddpath.addr := command.regno & "hfff".U
  when(command.transfer && (abstractcs.cmderr === 1.U)){
    when(command.write){
      io.ddpath.wdata := data0
      io.ddpath.validreq := true.B     
    } .elsewhen(!command.write) {
      data0 := io.ddpath.rdata
    }
    abstractcs.cmderr := 0.U
  }
}