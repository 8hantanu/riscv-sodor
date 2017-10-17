package zynqsimtop

import chisel3._
import zynq._
//import zynqsimtop._
import chisel3.util._
import chisel3.iotesters._
import chisel3.testers._
import config._
import Common._
import diplomacy._
import Common.Util._
import ReferenceChipBackend._
import scala.collection.mutable.ArrayBuffer
import scala.collection.mutable.HashMap
import junctions._
import uncore.axi4._
import unittest._
import junctions.NastiConstants._
import uncore.tilelink2._
import config.{Parameters, Field}
import RV32_3stage._
import RV32_3stage.Constants._

class SimDTM(implicit p: Parameters) extends BlackBox {
  val io = IO(new Bundle {
      val exit = Output(UInt(32.W))
      val debug = new DMIIO()
      val reset = Input(Bool())
      val clk = Input(Clock())
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

/** This includes the clock and reset as these are passed through the
  *  hierarchy until the Debug Module is actually instantiated. 
  *  
  */

class AXI4toDMI(top: Top)(implicit p: Parameters) extends Module {
  val io = IO(new Bundle {
    val ps_axi_slave = top.tile.io.ps_slave.cloneType
    val dmi = Flipped(new DMIIO())
    val success = Output(Bool())
  })
  val debugtlbase = p(DebugAddrSlave).base.U
  when(io.dmi.req.bits.op === DMConsts.dmi_OP_WRITE){
    io.ps_axi_slave(0).aw.valid := io.dmi.req.valid 
    io.ps_axi_slave(0).w.valid := io.dmi.req.valid
    io.ps_axi_slave(0).ar.valid := false.B
    io.dmi.req.ready := io.ps_axi_slave(0).aw.ready && io.ps_axi_slave(0).w.ready
  }
  when(io.dmi.req.bits.op === DMConsts.dmi_OP_READ){
    io.ps_axi_slave(0).aw.valid := false.B
    io.ps_axi_slave(0).w.valid := false.B
    io.ps_axi_slave(0).ar.valid := io.dmi.req.valid
    io.dmi.req.ready := io.ps_axi_slave(0).ar.ready
  }
  io.ps_axi_slave(0).aw.bits.addr := (debugtlbase | (io.dmi.req.bits.addr << 2))
  io.ps_axi_slave(0).aw.bits.size := 2.U
  io.ps_axi_slave(0).aw.bits.len := 0.U
  io.ps_axi_slave(0).aw.bits.id := 0.U
  io.ps_axi_slave(0).w.bits.data := io.dmi.req.bits.data
  io.ps_axi_slave(0).w.bits.last := 1.U

  io.ps_axi_slave(0).ar.bits.addr := (debugtlbase | (io.dmi.req.bits.addr << 2))
  io.ps_axi_slave(0).ar.bits.size := 2.U
  io.ps_axi_slave(0).ar.bits.len := 0.U
  io.ps_axi_slave(0).ar.bits.id := 0.U

  io.dmi.resp.valid := (io.ps_axi_slave(0).r.valid | io.ps_axi_slave(0).b.valid)
  io.dmi.resp.bits.data := io.ps_axi_slave(0).r.bits.data
  io.ps_axi_slave(0).r.ready := io.dmi.resp.ready
  io.ps_axi_slave(0).b.ready := io.dmi.resp.ready
}

class Top extends Module {
  implicit val inParams = (new WithZynqAdapter).alterPartial {
    case ExtMem => MasterConfig(base= 0x10000000L, size= 0x200000L, beatBytes= 4, idBits= 4)
  }
  val tile = LazyModule(new SodorTile()(inParams)).module
  val io = IO(new Bundle {
    val success = Output(Bool())
  })
  val axi4todmi = Module(new AXI4toDMI(this)(inParams))
  tile.io.ps_slave <> axi4todmi.io.ps_axi_slave
  io.success := axi4todmi.io.success
  val dtm = Module(new SimDTM()(inParams)).connect(clock, reset.toBool, axi4todmi.io.dmi, io.success)
}

object elaborate extends ChiselFlatSpec{
  def main(args: Array[String]): Unit = {
    chisel3.Driver.execute(args, () => new Top)
  }
}