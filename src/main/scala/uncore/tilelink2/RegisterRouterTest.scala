// See LICENSE for license details.

package uncore.tilelink2

import Chisel._

object LFSR16Seed
{
  def apply(seed: Int): UInt =
  {
    val width = 16
    val lfsr = Reg(init=UInt((seed*0x7231) % 65536, width))
    lfsr := Cat(lfsr(0)^lfsr(2)^lfsr(3)^lfsr(5), lfsr(width-1,1))
    lfsr
  }
}

class RRTestCombinational(val bits: Int, rvalid: Bool => Bool, wready: Bool => Bool) extends Module
{
  val io = new Bundle {
    val rvalid = Bool(OUTPUT)
    val rready = Bool(INPUT)
    val rdata  = UInt(OUTPUT, width = bits)
    val wvalid = Bool(INPUT)
    val wready = Bool(OUTPUT)
    val wdata  = UInt(INPUT, width = bits)
  }

  val rfire = io.rvalid && io.rready
  val wfire = io.wvalid && io.wready
  val reg = Reg(UInt(width = bits))

  io.rvalid := rvalid(rfire)
  io.wready := wready(wfire)

  io.rdata := reg
  when (wfire) { reg := io.wdata }
}

object RRTestCombinational
{
  private var seed = 42

  def always: Bool => Bool = _ => Bool(true)

  def random: Bool => Bool = { fire =>
    seed = seed + 1
    val lfsr = LFSR16Seed(seed)
    val reg = RegInit(Bool(true))
    reg := Mux(reg, !fire, lfsr(0) && lfsr(1))
    reg
  }

  def delay(x: Int): Bool => Bool = { fire =>
    val reg = RegInit(UInt(0, width = log2Ceil(x+1)))
    val ready = reg === UInt(0)
    reg := Mux(fire, UInt(x), Mux(ready, UInt(0), reg - UInt(1)))
    ready
  }

  def combo(bits: Int, rvalid: Bool => Bool, wready: Bool => Bool): RegField = {
    val combo = Module(new RRTestCombinational(bits, rvalid, wready))
    RegField(bits,
      RegReadFn { ready => combo.io.rready := ready; (combo.io.rvalid, combo.io.rdata) },
      RegWriteFn { (valid, data) => combo.io.wvalid := valid; combo.io.wdata := data; combo.io.wready })
  }
}

class RRTestRequest(val bits: Int,
  rflow: (Bool, Bool, UInt) => (Bool, Bool, UInt),
  wflow: (Bool, Bool, UInt) => (Bool, Bool, UInt)) extends Module
{
  val io = new Bundle {
    val rivalid = Bool(INPUT)
    val riready = Bool(OUTPUT)
    val rovalid = Bool(OUTPUT)
    val roready = Bool(INPUT)
    val rdata  = UInt(OUTPUT, width = bits)
    val wivalid = Bool(INPUT)
    val wiready = Bool(OUTPUT)
    val wovalid = Bool(OUTPUT)
    val woready = Bool(INPUT)
    val wdata  = UInt(INPUT, width = bits)
  }

  val (riready, rovalid, _)     = rflow(io.rivalid, io.roready, UInt(0, width = 1))
  val (wiready, wovalid, wdata) = wflow(io.wivalid, io.woready, io.wdata)
  val reg = Reg(UInt(width = bits))

  io.riready := riready
  io.rovalid := rovalid
  io.wiready := wiready
  io.wovalid := wovalid

  val rofire = io.roready && rovalid
  val wofire = io.woready && wovalid

  io.rdata := reg
  when (wofire) { reg := wdata }
}

object RRTestRequest
{
  private var seed = 1231
  def pipe(x: Int): (Bool, Bool, UInt) => (Bool, Bool, UInt) = { (ivalid, oready, idata) =>
    val full = RegInit(Vec.fill(x)(Bool(false)))
    val ready = Wire(Vec(x, Bool()))
    val data = Reg(Vec(x, UInt(width = idata.getWidth)))
    // Construct a classic bubble-filling pipeline
    ready(x-1) := oready || !full(x-1)
    when (ready(0)) { data(0) := idata }
    when (ready(0)) { full(0) := ivalid }
    ((ready.init zip ready.tail) zip full.init) foreach { case ((self, next), full) =>
      self := next || !full
    }
    ((data.init zip data.tail) zip ready.tail) foreach { case ((prev, self), ready) =>
      when (ready) { self := prev }
    }
    ((full.init zip full.tail) zip ready.tail) foreach { case ((prev, self), ready) =>
      when (ready) { self := prev }
    }
    (ready(0), full(x-1), data(x-1))
  }

  def busy: (Bool, Bool, UInt) => (Bool, Bool, UInt) = {
    seed = seed + 1
    (ivalid, oready, idata) => {
      val lfsr = LFSR16Seed(seed)
      val busy = RegInit(Bool(false))
      val data = Reg(UInt(width = idata.getWidth))
      val progress = RegInit(Bool(false))
      val iready = progress && !busy
      val ovalid = progress && busy
      when (progress) {
        busy := Mux(busy, !oready, ivalid)
        progress := Mux(busy, !oready, !ivalid)
      } .otherwise {
        progress := lfsr(0)
      }
      when (ivalid && iready) { data := idata }
      (iready, ovalid, data)
    }
  }

  def request(bits: Int,
    rflow: (Bool, Bool, UInt) => (Bool, Bool, UInt),
    wflow: (Bool, Bool, UInt) => (Bool, Bool, UInt)): RegField = {
    val request = Module(new RRTestRequest(bits, rflow, wflow))
    RegField(bits,
      RegReadFn { (rivalid, roready) => 
        request.io.rivalid := rivalid
        request.io.roready := roready
        (request.io.riready, request.io.rovalid, request.io.rdata) },
      RegWriteFn { (wivalid, woready, wdata) =>
        request.io.wivalid := wivalid
        request.io.woready := woready
        request.io.wdata := wdata
        (request.io.wiready, request.io.wovalid) })
  }
}

object RRTest0Map
{
  import RRTestCombinational._

  def aa(bits: Int) = combo(bits, always, always)
  def ar(bits: Int) = combo(bits, always, random)
  def ad(bits: Int) = combo(bits, always, delay(11))
  def ae(bits: Int) = combo(bits, always, delay(5))
  def ra(bits: Int) = combo(bits, random, always)
  def rr(bits: Int) = combo(bits, random, random)
  def rd(bits: Int) = combo(bits, random, delay(11))
  def re(bits: Int) = combo(bits, random, delay(5))
  def da(bits: Int) = combo(bits, delay(5), always)
  def dr(bits: Int) = combo(bits, delay(5), random)
  def dd(bits: Int) = combo(bits, delay(5), delay(5))
  def de(bits: Int) = combo(bits, delay(5), delay(11))
  def ea(bits: Int) = combo(bits, delay(11), always)
  def er(bits: Int) = combo(bits, delay(11), random)
  def ed(bits: Int) = combo(bits, delay(11), delay(5))
  def ee(bits: Int) = combo(bits, delay(11), delay(11))

  // All fields must respect byte alignment, or else it won't behave like an SRAM
  val map = Seq(
    0 -> Seq(aa(8), ar(8), ad(8), ae(8)),
    1 -> Seq(ra(8), rr(8), rd(8), re(8)),
    2 -> Seq(da(8), dr(8), dd(8), de(8)),
    3 -> Seq(ea(8), er(8), ed(8), ee(8)),
    4 -> Seq(aa(3), ar(5), ad(1), ae(7), ra(2), rr(6), rd(4), re(4)),
    5 -> Seq(da(3), dr(5), dd(1), de(7), ea(2), er(6), ed(4), ee(4)),
    6 -> Seq(aa(8), rr(8), dd(8), ee(8)),
    7 -> Seq(ar(8), rd(8), de(8), ea(8)))
}

object RRTest1Map
{
  import RRTestRequest._

  def pp(bits: Int) = request(bits, pipe(3), pipe(3))
  def pb(bits: Int) = request(bits, pipe(3), busy)
  def bp(bits: Int) = request(bits, busy, pipe(3))
  def bb(bits: Int) = request(bits, busy, busy)

  val map = RRTest0Map.map.take(6) ++ Seq(
    6 -> Seq(pp(8), pb(8), bp(8), bb(8)),
    7 -> Seq(pp(3), pb(5), bp(1), bb(7), pb(5), bp(3), pp(4), bb(4)))
}

trait RRTest0Bundle
{
}

trait RRTest0Module extends HasRegMap
{
  regmap(RRTest0Map.map:_*)
}

class RRTest0(address: BigInt) extends TLRegisterRouter(address, 0, 32, Some(0), 4)(
  new TLRegBundle((), _)    with RRTest0Bundle)(
  new TLRegModule((), _, _) with RRTest0Module)

trait RRTest1Bundle
{
}

trait RRTest1Module extends Module with HasRegMap
{
  val clocks = Module(new ClockDivider)
  clocks.io.clock_in := clock
  clocks.io.reset_in := reset

  def x(bits: Int) = {
    val field = UInt(width = bits)

    val readCross = Module(new RegisterReadCrossing(field))
    readCross.io.master_clock := clock
    readCross.io.master_reset := reset
    readCross.io.master_allow := Bool(true)
    readCross.io.slave_clock := clocks.io.clock_out
    readCross.io.slave_reset := clocks.io.reset_out
    readCross.io.slave_allow := Bool(true)

    val writeCross = Module(new RegisterWriteCrossing(field))
    writeCross.io.master_clock := clock
    writeCross.io.master_reset := reset
    writeCross.io.master_allow := Bool(true)
    writeCross.io.slave_clock := clocks.io.clock_out
    writeCross.io.slave_reset := clocks.io.reset_out
    writeCross.io.slave_allow := Bool(true)

    readCross.io.slave_register := writeCross.io.slave_register
    RegField(bits, readCross.io.master_port, writeCross.io.master_port)
  }

  val map = RRTest1Map.map.drop(1) ++ Seq(0 -> Seq(x(8), x(8), x(8), x(8)))
  regmap(map:_*)
}

class RRTest1(address: BigInt) extends TLRegisterRouter(address, 0, 32, Some(6), 4)(
  new TLRegBundle((), _)    with RRTest1Bundle)(
  new TLRegModule((), _, _) with RRTest1Module)
