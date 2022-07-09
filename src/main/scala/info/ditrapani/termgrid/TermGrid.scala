package info.ditrapani.termgrid

import org.jline.keymap.{BindingReader, KeyMap}
import KeyMap.{ctrl, esc, key}
import org.jline.terminal.{Terminal, TerminalBuilder}
import org.jline.utils.InfoCmp.Capability
import zio.{Task, Queue, Schedule, UIO, ZIO}

trait ITermGrid:
  def clear(): UIO[Unit]
  def draw(): UIO[Unit]
  def reset(): UIO[Unit]
  def set(y: Int, x: Int, char: Char, fg: Int, bg: Int): UIO[Unit]
  def textk(y: Int, x: Int, text: String, fg: Int, bg: Int): UIO[Unit]
  def terminal: Terminal

trait Action[T]:
  val keys: List[String]
  def convert: T

class TermGrid(height: Int, width: Int, override val terminal: Terminal) extends ITermGrid:
  def clear(): UIO[Unit] = ???
  def draw(): UIO[Unit] = ???
  def reset(): UIO[Unit] = ???
  def set(y: Int, x: Int, char: Char, fg: Int, bg: Int): UIO[Unit] = ???
  def textk(y: Int, x: Int, text: String, fg: Int, bg: Int): UIO[Unit] = ???

def newTermGrid(height: Int, width: Int): UIO[ITermGrid] =
  require(height >= 1, "Height must be positive.")
  require(width >= 1, "Width must be positive.")
  ZIO.attemptBlocking {
    val terminal = TerminalBuilder.terminal().nn
    TermGrid(height, width, terminal)
  }.orDie

def inputLoop[T](actions: List[Action[T]], eventQueue: Queue[T], termGrid: ITermGrid): UIO[Unit] =
  val keyMap = new KeyMap[T]()
  actions.foreach { action =>
    keyMap.bind(action.convert, action.keys*)
  }
  val terminal = termGrid.terminal
  terminal.enterRawMode()
  val bindingReader = new BindingReader(terminal.reader())
  val operation: UIO[Unit] = {
    val action = bindingReader.readBinding(keyMap).nn
    eventQueue.offer(action).map(_ => (): Unit)
  }
  operation.repeat(Schedule.recurs(5)).map(_ => (): Unit)

def repl[T](actions: List[Action[T]], termGrid: ITermGrid)(logic: T => UIO[Unit]): UIO[Unit] = ???
