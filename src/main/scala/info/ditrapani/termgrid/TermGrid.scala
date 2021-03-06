package info.ditrapani.termgrid

import colors.colorMap6To8
import org.jline.keymap.{BindingReader, KeyMap}
import org.jline.terminal.{Terminal, TerminalBuilder}
import scala.collection.mutable.ArraySeq
import zio.{Console, Ref, Queue, Task, Schedule, UIO, ZIO}

/** Waits for keypresses and puts the Key on the queue after transforming it into a T
  *
  * @param loopControlRef
  *   The input loop will shutdown when this is Stop. The initial value should be Loop. The
  *   application code should set the ref to Stop when input is no longer needed or app is ready to
  *   shutdown.
  * @param eventQueue
  *   The loop will convert each Key into a T and offer it to this Queue for the application to
  *   process at its leisure. The Queue is intended to service multiple event streams, hence why the
  *   application author gets to pick the T.
  * @param termGrid
  *   The instance of termGrid to listen for key presses on.
  *
  * Intended for multi event source apps, for single-event source use inputLoop instead. The
  * eventQueue is intended to be shared across mulitple event streams (like timers, websockets,
  * kafka, etc).
  */
def inputLoop[T](loopControlRef: Ref[LoopControl], eventQueue: Queue[T], termGrid: ITermGrid)(
    convert: Key => T,
): UIO[Unit] =
  val terminal = termGrid.terminal
  ZIO
    .attemptBlocking {
      terminal.enterRawMode()
    }
    .orDie
    .flatMap { _ =>
      import org.jline.utils.NonBlockingReader
      val keyMap = makeKeyMapping(terminal)
      val bindingReader = new BindingReader(terminal.reader())
      val operation: UIO[LoopControl] = {
        for
          key <- ZIO.attemptBlocking { bindingReader.readBinding(keyMap).nn }.orDie
          _ <- eventQueue.offer(convert(key))
          loopControl <- loopControlRef.get
        yield loopControl
      }
      operation.repeat(Schedule.recurUntil(_.toBool)).map(_ => (): Unit)
    }

/** Waits for keypresses and executes logic on each Key
  *
  * @param termGrid
  *   The instance of termGrid to listen for key presses on.
  *
  * @param logic
  *   The logic to execute on each key press. The return value of this function will be used to
  *   decide if the repl should loop again or stop.
  *
  * Intended for applications whose only event source is user key presses. For multi-event source
  * apps, use inputLoop instead. The eventQueue is intended to be shared across multiple event
  * streams (like timers, websockets messages, kafka records, etc).
  */
def repl(termGrid: ITermGrid)(logic: Key => UIO[LoopControl]): UIO[Unit] =
  val terminal = termGrid.terminal
  ZIO
    .attemptBlocking {
      terminal.enterRawMode()
    }
    .orDie
    .flatMap { _ =>
      val keyMap = makeKeyMapping(terminal)
      val bindingReader = new BindingReader(terminal.reader())
      val operation: UIO[LoopControl] = {
        for
          keyCode <- ZIO.attemptBlocking { bindingReader.readBinding(keyMap).nn }.orDie
          loopControl <- logic(keyCode)
        yield loopControl
      }
      // TODO: change to recurUntil; and need continue function?
      operation.repeat(Schedule.recurUntil(_.toBool)).map(_ => (): Unit)
    }

enum LoopControl(val toBool: Boolean):
  case Loop extends LoopControl(false)
  case Stop extends LoopControl(true)

/** Represents the terminal as a 2D grid with 64 colors. Each cell has a foreground color fg, a
  * background color bg and a unicode text character. Create an instance with the makeTermGrid
  * factory function.
  *
  * <p>Typical usage might have a setup, a core function, and a shutdown.
  *
  * <p>Setup: the setup would call clear() to clean up the screen. Then attach an input event
  * handler function with the onInput method. The handler should either be the core function or call
  * the core function.
  *
  * <p> Core function: called for each input/other event. Example:
  *
  * <ul> <li>Interpret event <li>Apply business logic <li>Update grid with several calls to set()
  * and/or text() methods <li>Call draw() to display the new grid state on the terminal </ul>
  *
  * <p>Shutdown: call reset() to return the terminal to normal.
  *
  * <p>A fg or bg color is a 6-bit RGB color from a 4x4x4 RGB color cube. A value in the range
  * [0-63] inclusive. The bits in the number are RRGGBB. In other words, 2 bits per color component;
  * in order red, green, then blue. For example if the color is 0b011011, then red is 1, green is 2
  * and blue is 3.
  */
trait ITermGrid:
  /** Clears the screen with the current background color. Literally prints "\\u001b[2J".
    */
  def clear(): UIO[Unit]

  /** Draw the current state of the grid to the terminal. */
  def draw(): UIO[Unit]

  /** Reset colors and re-enable the cursor. Literally prints "\\u001b[0m\\u001B[?25h" */
  def reset(): UIO[Unit]

  /** Set a cell in the grid. You must call draw() to see the change in the terminal.
    *
    * @param y
    *   0-based row index into grid
    * @param x
    *   0-based column index into grid
    * @param c
    *   character to set in cell
    * @param fg
    *   6-bit RGB foreground color to set for the cell. Must be in range [0-63] inclusive.
    * @param bg
    *   6-bit RGB background color to set for the cell. Must be in range [0-63] inclusive.
    */
  def set(y: Int, x: Int, char: Char, fg: Int, bg: Int): UIO[Unit]

  /** Set a sequence of cells of a row in the grid. Effects n cells where n is the length of text.
    * You must call draw() to see the change in the terminal.
    *
    * @param y
    *   0-based row index into grid
    * @param x
    *   0-based column index into grid
    * @param text
    *   text to write in row y starting in column x
    * @param fg
    *   6-bit foreground color to set to each cell for the text. Must be in range [0-63] inclusive.
    * @param bg
    *   6-bit background color to set to each cell. Must be in range [0-63] inclusive.
    */
  def text(y: Int, x: Int, text: String, fg: Int, bg: Int): UIO[Unit]

  /** Return the underlying jline Terminal */
  def terminal: Terminal

/** Create a TermGrid
  */
def newTermGrid(height: Int, width: Int): UIO[ITermGrid] =
  require(height >= 1, "Height must be positive.")
  require(width >= 1, "Width must be positive.")
  for terminal <- ZIO.attemptBlocking { TerminalBuilder.terminal().nn }.orDie
  yield {
    val grid: ArraySeq[ArraySeq[Cell]] = {
      val fg = colorMap6To8(colors.darkPurple)
      val bg = colorMap6To8(colors.lightGrey)
      ArraySeq.fill[Cell](height, width)(Cell('.', fg, bg))
    }
    val sb: StringBuilder =
      new StringBuilder(TermGrid.init.length + height * width * TermGrid.cellWidth + height)
    TermGrid(height, width, terminal, grid, sb)
  }

private object TermGrid:
  val clear = "\u001b[2J"
  val init = "\u001B[?25l\u001b[0;0H"
  val reset = "\u001b[0m\u001B[?25h"
  val cellWidth = 23

private class TermGrid(
    height: Int,
    width: Int,
    override val terminal: Terminal,
    grid: ArraySeq[ArraySeq[Cell]],
    sb: StringBuilder,
) extends ITermGrid:
  import TermGrid.cellWidth

  def clear(): UIO[Unit] =
    Console.printLine(TermGrid.clear).orDie

  def draw(): UIO[Unit] =
    for
      sb <- ZIO.attempt {
        // Each cell needs 23 chars in the string buffer:
        // - 11 to set fg color
        // - 11 to set bg color
        // - 1 for utf8 unicode char
        // There are height * width cells
        // Need to add 1 newline char for each line (= height)
        sb.clear()
        sb.append(TermGrid.init)
        grid.foreach { row =>
          row.foreach { cell =>
            // We want to keep the string builder at the exact same length
            // so let's pad the color numbers with up to 3 0s.
            val fg = f"${cell.fg}%03d"
            val bg = f"${cell.bg}%03d"
            sb.append(s"\u001b[38;5;${fg}m")
            sb.append(s"\u001b[48;5;${bg}m")
            sb.append(cell.char)
          }
          sb.append('\n')
        }
        sb
      }.orDie
      _ <- Console.printLine(sb).orDie
    yield (): Unit

  def reset(): UIO[Unit] =
    // TODO: should exit raw mode here, right?  But don't know how in jline...
    Console.printLine(TermGrid.reset).orDie

  def set(y: Int, x: Int, char: Char, fg: Int, bg: Int): UIO[Unit] =
    ZIO.attempt {
      checkBounds(y, x)
      checkColors(fg, bg)
      unsafeSet(y, x, char, colorMap6To8(fg), colorMap6To8(bg))
    }.orDie

  def text(y: Int, x: Int, text: String, fg: Int, bg: Int): UIO[Unit] =
    ZIO.attempt {
      checkBounds(y, x)
      checkColors(fg, bg)
      val fg8Bit = colorMap6To8(fg)
      val bg8Bit = colorMap6To8(bg)
      require(x + text.length <= width, "x + text.length must be <= grid width")
      for (i <- 0 until text.length) {
        val xIndex: Int = x + i
        val char: Char = text.charAt(i)
        unsafeSet(y, xIndex, char, fg8Bit, bg8Bit)
      }
    }.orDie

  private def checkBounds(y: Int, x: Int): Unit =
    require(y >= 0 && y < height, "y index must be >= 0 and < grid height")
    require(x >= 0 && x < width, "x index must be >= 0 and < grid width")

  private def unsafeSet(y: Int, x: Int, char: Char, fg8: Int, bg8: Int): Unit =
    val cell = grid(y)(x)
    cell.char = char
    cell.fg = fg8
    cell.bg = bg8

case class Cell(var char: Char, var fg: Int, var bg: Int)

def checkColors(fg: Int, bg: Int): Unit =
  checkColor(fg, "foreground", "fg")
  checkColor(bg, "background", "bg")

def checkColor(color: Int, desc: String, name: String): Unit =
  require(
    color <= 63 && color >= 0,
    s"6-bit ${desc} color ${name} must be in range [0, 63] inclusive",
  )
