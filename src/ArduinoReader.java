
import jssc.SerialPortException;

import java.awt.*;
import java.awt.event.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.prefs.Preferences;
import javax.swing.*;
import javax.swing.text.Document;

/**
 *  ArduinoReader a program to talk to Arduino BootLoaders
 *  Author: Wayne Holder, 2017
 *  License: MIT (https://opensource.org/licenses/MIT)
 */
public class ArduinoReader extends JFrame {
  private static final boolean        DEBUG = false;
  private static final boolean        skipFF = true;
  private enum                        Protocol {STKV1, CATERINA, STKV2}
  private static Font                 tFont;
  private static Map<String,MCU> devices = new HashMap<>();
  private transient Preferences       prefs = Preferences.userRoot().node(this.getClass().getName());
  private transient JSSCPort          jPort;
  private JEditorPane                 text;
  private int                         tryFirst = 0;
  private boolean                     firstTime = true;

  static class MCU {
    String  name;
    int     flashSize, shift, base;
    char    fuse;

    MCU (String name, int flashSize, char fuse, int shift, int base) {
      this.name = name;
      this.flashSize = flashSize;
      this.fuse = fuse;
      this.shift = shift;
      this.base = base;
    }

    /**
     * Calculates size of bootloader
     * @param fuses byte[] array of fuses, or null
     * @return size of bootloader in words
     */
    int getBootSize (byte[] fuses) {
      if (fuses != null && fuses.length == 3) {
        if (fuse == 'H') {
          int shift = (fuses[1] >> 1) & 0x03;
          return base << (3 - shift);
        } else if (fuse == 'E') {
          int shift = (fuses[2] >> 1) & 0x03;
          return base << (3 - shift);
        }
      }
      return base << 3;   // Assume max size, if fuses are not available
    }

    int getMaxBootSize () {
      return (base << 3) * 2;
    }
  }

  static {
    String os = System.getProperty("os.name").toLowerCase();
    if (os.contains("win")) {
      tFont = new Font("Consolas", Font.PLAIN, 12);
    } else if (os.contains("mac")) {
      tFont = new Font("Menlo", Font.PLAIN, 12);
    } else if (os.contains("linux")) {
      tFont = new Font("Courier", Font.PLAIN, 12);
    } else {
      tFont = new Font("Courier", Font.PLAIN, 12);
    }
    // STK500V1-based Arduino Microcontrollers                              Flash  Boot (words)
    devices.put("1E930A", new MCU("ATmega88A",   0x02000, 'E', 1, 128));   // 8K,   1K
    devices.put("1E930F", new MCU("ATmega88PA",  0x02000, 'E', 1, 128));   // 8K,   1K
    devices.put("1E9406", new MCU("ATmega168A",  0x04000, 'E', 1, 256));   // 16K,  1K
    devices.put("1E940B", new MCU("ATmega168PA", 0x04000, 'E', 1, 256));   // 16K,  1K
    devices.put("1E9514", new MCU("ATmega328",   0x08000, 'H', 1, 256));   // 32K,  2K
    devices.put("1E950F", new MCU("ATmega328P",  0x08000, 'H', 1, 256));   // 32K,  2K
    // Caterina-based Arduino Microcontrollers
    devices.put("1E9488", new MCU("ATmega16U4",  0x10000, 'H', 1, 256));   // 64K,  2K
    devices.put("1E9587", new MCU("ATmega32U4",  0x08000, 'H', 1, 256));   // 32K,  2K
    // STK500V2-based Arduino Microcontrollers
    devices.put("1E9608", new MCU("ATmega640",   0x10000, 'H', 1, 512));   // 64K,  4K
    devices.put("1E9703", new MCU("ATmega1280",  0x20000, 'H', 1, 512));   // 128K, 4K
    devices.put("1E9704", new MCU("ATmega1281",  0x20000, 'H', 1, 512));   // 128K, 4K
    devices.put("1E9801", new MCU("ATmega2560",  0x40000, 'H', 1, 512));   // 256K, 4K
    devices.put("1E9802", new MCU("ATmega2561",  0x40000, 'H', 1, 512));   // 256K, 4K
  }

  /*
   *  See:
   *    https://baldwisdom.com/bootloading/
   *    https://github.com/Optiboot/optiboot/wiki/HowOptibootWorks
   *    https://github.com/dhylands/projects/blob/master/host/boothost/stk500-command.h
   *    https://www.instructables.com/id/Overview-the-Arduino-sketch-uploading-process-and-/
   *    https://github.com/arduino/Arduino-stk500v2-bootloader/blob/master/command.h
   *
   *  To Do:
   *    1. Implement STV500v2 Protocol to talk to Mega2560 Board (256K Flash not addressable with v1)
   *
   *  Baud Rates:
   *    UNO = 115200
   *    Duemilanove, Nano = 57600
   *    Diecemila or Duemilanove with ATmega168 = 19200
   *
   *  STK500 Commands:              Optiboot Support      Function
   *    0x20  CRC_EOP               Yes                   Ends commands
   *    0x30  STK_GET_SYNC          Yes                   Get Synchronization
   *    0x32  STK_GET_SIGN_ON       --                    Check if Starterkit Present
   *    0x40  STK_SET_PARAMETER     --                    Set Parameter Value
   *    0x41  STK_GET_PARAMETER     Only 0x81 and 0x82    Get Parameter Value
   *    0x42  STK_SET_DEVICE        Ignored               Set Device Programming Parameters
   *    0x45  STK_SET_DEVICE_EXT    Ignored               Set Extended Device Programming Parameters
   *    0x50  STK_ENTER_PROGMODE     --                   Enter Program Mode
   *    0x51  STK_LEAVE_PROGMODE     --                   Leave Program Mode
   *    0x52  STK_CHIP_ERASE         --                   Chip Erase
   *    0x53  STK_CHECK_AUTOINC      --                   Check for Address Autoincrement
   *    0x55  STK_LOAD_ADDRESS      Yes                   Load Address
   *    0x56  STK_UNIVERSAL         Ignored               Universal Command
   *    0x57  STK_UNIVERSAL_MULTI   --                    Extended Universal Command
   *    0x60  STK_PROG_FLASH        --                    Program Flash Memory
   *    0x61  STK_PROG_DATA         --                    Program Data Memory
   *    0x62  STK_PROG_FUSE         --                    Program Fuse Bits
   *    0x63  STK_PROG_LOCK         --                    Program Lock Bits
   *    0x64  STK_PROG_PAGE         Flash Only            Program Page
   *    0x65  STK_PROG_FUSE_EXT     --                    Program Fuse Bits Extended
   *    0x70  STK_READ_FLASH        --                    Read Flash Memory
   *    0x71  STK_READ_DATA         --                    Read Data Memory
   *    0x72  STK_READ_FUSE         --                    Read Fuse Bits
   *    0x73  STK_READ_LOCK         --                    Read Lock Bits
   *    0x74  STK_READ_PAGE         Flash Only            Read Page
   *    0x75  STK_READ_SIGN         Yes                   Read Signature Bytes
   *    0x76  STK_READ_OSCCAL       --                    Read Oscillator Calibration Byte
   *    0x77  STK_READ_FUSE_EXT     --                    Read Fuse Bits Extended
   *    0x78  STK_READ_OSCCAL_EXT   --                    Read Oscillator Calibration Byte Extended
   *
   *  Response Codes
   *    0x10  STK_OK
   *    0x11  STK_FAILED
   *    0x12  STK_UNKNOWN
   *    0x13  STK_NODEVICE
   *    0x14  STK_INSYNC
   *    0x15  STK_NOSYNC
   *
   *  Optiboot Command Subset:
   *    0x55 <addr_low> <addr_high> 0x20                Load Address
   *        Response: 0x14 0x10                         (ok)
   *
   *    0x64 <size high> <size low> 'F' <data> 0x20     Program Flash Page (length <= 256)
   *        Response: 0x14 0x10                         (ok)
   *
   *    0x74 <size high> <size low> 'F' 0x20            Read Flash Page (length <= 256)
   *        Response: 0x14 <data> 0x10                  (read OK)
   *
   *    0x75 0x20                                       Read Signature Bytes
   *        Response: 0x14 <high> <mid> <low> 0x10      (read ok)
   *
   *    0x51 0x20                                       Leave programming mode
   *        Response: 0x14 <data> 0x10                  (read OK)
   *
   *    0x41 <parm> 0x20                                Get Parameter Value (major (0x81) minor (0x82) SW version only)
   *        Response: 0x14 <value> 0x10                 (read ok)
   *
   *  STK500 Commands:
   *    0x77 0x20                                       Read Fuse Bits Extended
   *        Response: 0x14 <low> <high> <ext> 0x20      (read ok)
   *
   *  Caterina Bootloader Commands                      Typical Response
   *    E - Exit Bootloader                             0x0D
   *    T - Select Device Type                          0x0D
   *    L - Leave Programming Mode                      0x0D
   *    P - Enter Programming Mode                      0x0D
   *    t - Return Supported Device Codes               0x44 0x00
   *    a - Auto Increment Address                      'Y'
   *    A - Set Address (word address) <high> <low>     0x0D
   *    p - Return Programmer Type                      'S' (serial programmer)
   *    S - Return Software Identifier                  "CATERIN" (only first 7 characters)
   *    V - Return Software Version                     "10"
   *    s - Read Signature Bytes                        0x?? 0x?? 0x?? (example: 0x87, 0x95, 0x1E)
   *    e - Chip Erase                                  0x0D
   *    l - Write Lock Bits                             0x0D
   *    r - Read Lock Bits                              0x?? (lock bits)
   *    F - Read Fuse Bits                              0x?? (low)
   *    N - Read High Fuse Bits                         0x?? (high)
   *    Q - Read Extended Fuse Bits                     0x?? (extended)
   *    b - Check Block Support
   *    R - Read Program Memory                         0x?? 0x?? (order is <high> <low>)
   *    g - Read Block of Memory <high> <low> 'F'       0x?? 0x?? * block size (in words)
   *
   *  STK500 V2 Commands                                Response
   *    0x1B  MESSAGE_START
   *    0x0E  TOKEN
   *    0x01  CMD_SIGN_ON
   *    0x06  CMD_LOAD_ADDRESS (4 bytes, MSB first)     CMD_LOAD_ADDRESS STATUS_CMD_OK
   *    0x03  CMD_GET_PARAMETER <parm num>              CMD_GET_PARAMETER STATUS_CMD_OK <value>
   *    0x1B  CMD_READ_SIGNATURE_ISP
   *    0x14  CMD_READ_FLASH_ISP
   *    0x18  CMD_READ_FUSE_ISP
   *
   *  STK500 V2 Constants
   *    0x00  STATUS_CMD_OK
   *
   *  STK500 V2 Parameters (Read Only)
   *    0x80  PARAM_BUILD_NUMBER_LOW
   *    0x81  PARAM_BUILD_NUMBER_HIGH
   *    0x90  PARAM_HW_VER
   *    0x91  PARAM_SW_MAJOR
   *    0x92  PARAM_SW_MINOR
   */

  static class Unsupported extends Exception {
    String  message;
    Unsupported (String message) {
      this.message = message;
    }
  }

  class ArduinoBootDriver implements JSSCPort.RXEvent {
    private JSSCPort          jPort;
    ByteArrayOutputStream     bout = new ByteArrayOutputStream();
    private int               len;
    private byte              checksum, sendSeq;
    private volatile int      state, timeout;
    private volatile Protocol  protocol;

    ArduinoBootDriver (JSSCPort jPort) {
      this.jPort = jPort;
    }

    private void close () {
      jPort.close();
    }

    boolean sync () throws Exception {
      try {
        for (int ii = 0; ii < 3; ii++) {
          int type = (ii + tryFirst) % 3;
          switch (type) {
          case 0:
            protocol = Protocol.STKV1;
            if (jPort.open(this)) {
              // Toggle DTR to RESET Arduino
              jPort.setDTR(false);
              Thread.sleep(100);
              jPort.setDTR(true);
              for (int retry = 0; retry < 5; retry++) {
                if (sendCmd(new byte[]{0x30, 0x20}, 0) != null) {
                  if (firstTime || type != tryFirst) {
                    appendText("STKV1-based Bootloader detected\n");
                    firstTime = false;
                  }
                  tryFirst = 0;
                  return true;
                }
              }
              jPort.close();
            }
            break;
          case 1:
            protocol = Protocol.CATERINA;
            jPort.touch1200();
            if (jPort.open(this)) {
              byte[] data = sendCmd(new byte[]{'S'}, 7);
              // Note: bootloader only returns first 7 bytes of name
              if (data.length == 7 && "CATERIN".equals(new String(data, StandardCharsets.UTF_8))) {
                if (firstTime || type != tryFirst) {
                  appendText("Caterina-based Bootloader detected\n");
                  firstTime = false;
                }
                tryFirst = 1;
                return true;
              }
              jPort.close();
            }
            break;
          case 2:
            protocol = Protocol.STKV2;
            if (jPort.open(this)) {
              // Toggle DTR to RESET Arduino
              jPort.setDTR(false);
              Thread.sleep(100);
              jPort.setDTR(true);
              for (int retry = 0; retry < 5; retry++) {
                byte[] rsp = sendCmd(new byte[]{0x01}, 8);
                if (rsp != null) {
                  //System.out.println(new String(rsp, StandardCharsets.US_ASCII));
                  if (firstTime || type != tryFirst) {
                    appendText("STKV2-based Bootloader detected\n");
                    firstTime = false;
                  }
                  tryFirst = 2;
                  return true;
                }
              }
              jPort.close();
            }
            break;
          }
        }
      } catch (SerialPortException ex) {
        throw new IllegalStateException("Unable to Open Serial Port");
      }
      jPort.close();
      return false;
    }

    byte[] sendCmd (byte[] cmd, int bytes) throws Exception {
      if (DEBUG) {
        System.out.print("sendCmd(): ");
        for (byte cc : cmd) {
          System.out.print(toHex(cc) + " ");
        }
        System.out.println(" - " + bytes);
      }
      if (protocol == Protocol.CATERINA) {
        setupInput(bytes);
        jPort.sendBytes(cmd);
        waitForResponse();
        return bout.toByteArray();
      } else if (protocol == Protocol.STKV1)  {
        setupInput(bytes);
        jPort.sendBytes(cmd);
        waitForResponse();
        return state == 3 ? bout.toByteArray() : null;
      } else if (protocol == Protocol.STKV2)  {
        byte[] buf = new byte[6 + cmd.length];
        byte chk = 0;
        chk ^= buf[0] = 0x1B;
        chk ^= buf[1] = sendSeq;
        chk ^= buf[2] = (byte) (cmd.length >> 8);
        chk ^= buf[3] = (byte) (cmd.length & 0xFF);
        chk ^= buf[4] = 0x0E;
        int idx = 0;
        while (idx < cmd.length) {
          chk ^= buf[5 + idx] = cmd[idx++];
        }
        buf[5 + idx] = chk;
        sendSeq++;
        setupInput(bytes);
        jPort.sendBytes(buf);
        waitForResponse();
        return state == 7 ? bout.toByteArray() : null;
      }
      return null;
    }

    private void setupInput (int bytes) {
      if (protocol == Protocol.CATERINA) {
        bout.reset();
        timeout = 50;
        len = bytes;
      } else if (protocol == Protocol.STKV1)  {
        state = 0;
        bout.reset();
        len = bytes;
        timeout = 100;
      } else if (protocol == Protocol.STKV2)  {
        state = 0;
        bout.reset();
        len = bytes;
        timeout = 50;
      }
    }

    private void waitForResponse () throws Exception {
      if (protocol == Protocol.CATERINA) {
        while (timeout > 0) {
          if (len == 0) {
            return;
          }
          Thread.sleep(10);
          synchronized (this) {
            timeout--;
          }
        }
      } else if (protocol == Protocol.STKV1)  {
        while (timeout > 0) {
          if (state == 3) {
            return;
          }
          Thread.sleep(10);
          synchronized (this) {
            timeout--;
          }
        }
      } else if (protocol == Protocol.STKV2)  {
        while (timeout > 0) {
          if (state == 7) {
            return;
          }
          Thread.sleep(10);
          synchronized (this) {
            timeout--;
          }
        }
      }
      if (DEBUG) {
        System.out.println("TIMEOUT bout.size() = " + bout.size() + protocol + " state = " + state);
      }
    }

    /**
     * Read from Flasm Memory
     * @param addr address in bytes
     * @param length number of bytes to read
     */
    byte[] readFlash (int addr, int length) throws Exception {
      System.out.println("readFlash()");
      ByteArrayOutputStream buf = new ByteArrayOutputStream();
      if (protocol == Protocol.CATERINA) {
        int wordAddr = addr >> 1;
        byte[] data;
        data = sendCmd(new byte[]{'A', (byte) (wordAddr >> 8), (byte) (wordAddr & 0xFF)}, 1);
        if (data.length == 1 && data[0] == 0x0D) {
          data = sendCmd(new byte[]{'g', (byte) (length >> 8), (byte) (length & 0xFF), 'F'}, length);
          if (data.length == length) {
            return data;
          }
        }
        return null;
      } else if (protocol == Protocol.STKV1)  {
        int blockSize = 256;
        read_Loop:
        while (length > 0) {
          int len = length > blockSize ? blockSize : length;
          int wordAddr = addr >> 1;
          if (DEBUG) {
            System.out.println("addr: " + toHex(addr) + ", len: " + len + ", length: " + length);
          }
          for (int ii =  0; ii < 4; ii++) {
            if (sendCmd(new byte[]{0x55, (byte) (wordAddr & 0xFF), (byte) (wordAddr >> 8), 0x20}, 0) == null) {
              return null;
            }
            byte[] rsp = sendCmd(new byte[]{0x74, (byte) (len >> 8), (byte) (len & 0xFF), 'F', 0x20}, len);
            if (rsp!= null) {
              buf.write(rsp, 1, len);
              length -= blockSize;
              addr += blockSize;
              continue read_Loop;
            }
            if (DEBUG) {
              System.out.println("Retry");
            }
          }
          if (DEBUG) {
            System.out.println("Retry Failed");
          }
          return null;
        }
        return buf.toByteArray();
      } else if (protocol == Protocol.STKV2)  {
        int wordAddr = addr >> 1;
        // CMD_LOAD_ADDRESS
        byte[] data = sendCmd(new byte[]{0x06, (byte) (wordAddr >> 24), (byte) (wordAddr >> 16),
                                               (byte) (wordAddr >> 8), (byte) (wordAddr & 0xFF)}, 2);
        if (data != null && data.length == 2 && data[1] == 0) {
          // CMD_READ_FLASH_ISP
          data = sendCmd(new byte[]{0x14, (byte) (length >> 8), (byte) (length & 0xFF), 0x00}, length + 3);
          if (data != null && data.length == length + 3) {
            byte[] ret = new byte[length];
            System.arraycopy(data, 2, ret, 0, length);
            return ret;
          }
        }
        throw new Unsupported("Not Implemented");
      }
      return null;
    }

    byte[] getSignature () throws Exception {
      if (protocol == Protocol.CATERINA) {
        byte[] data = sendCmd(new byte[]{'s'}, 3);
        if (data.length == 3) {
          return new byte[] {data[2], data[1], data[0]};
        }
      } else if (protocol == Protocol.STKV1)  {
        byte[] rsp = sendCmd(new byte[]{0x75, 0x20}, 3);
        if (rsp != null && rsp.length == 5) {
          return new byte[]{rsp[1], rsp[2], rsp[3]};
        }
      } else if (protocol == Protocol.STKV2)  {
        byte[] sigH = sendCmd(new byte[]{0x1B, 0x00, 0x00, 0x00, 0, 0x00}, 4);
        if (sigH != null && sigH.length == 4) {
          byte[] sigM = sendCmd(new byte[]{0x1B, 0x00, 0x00, 0x00, 1, 0x00}, 4);
          if (sigM != null && sigM.length == 4) {
            byte[] sigL = sendCmd(new byte[]{0x1B, 0x00, 0x00, 0x00, 2, 0x00}, 4);
            return new byte[]{sigH[2], sigM[2], sigL[2]};
          }
        }
        throw new Unsupported("Not Implemented");
      }
      return null;
    }

    byte[] getFuses () throws Exception {
      if (protocol == Protocol.CATERINA) {
        byte[] lFuse = sendCmd(new byte[]{'F'}, 1);
        if (lFuse.length == 1) {
          byte[] hFuse = sendCmd(new byte[]{'N'}, 1);
          if (hFuse.length == 1) {
            byte[] eFuse = sendCmd(new byte[]{'Q'}, 1);
            if (eFuse.length == 1) {
              return new byte[]{lFuse[0], hFuse[0], eFuse[0]};
            }
          }
        }
      } else if (protocol == Protocol.STKV1)  {
        byte[] rsp = sendCmd(new byte[]{0x72, 0x20}, 3);
        if (rsp != null && rsp.length == 5) {
          return new byte[]{rsp[1], rsp[2], rsp[3]};
        }
      } else if (protocol == Protocol.STKV2)  {
        byte[] fuseH = sendCmd(new byte[]{0x18, 0x00, 0x00, 0x00, 0x00, 0x00}, 4);
        if (fuseH != null && fuseH.length == 4) {
          byte[] fuseL = sendCmd(new byte[]{0x18, 0x00, 0x50, 0x00, 0x00, 0x00}, 4);
          if (fuseL != null && fuseL.length == 4) {
            byte[] fuseE = sendCmd(new byte[]{0x18, 0x00, 0x50, 0x08, 0x00, 0x00}, 4);
            return new byte[]{fuseL[2], fuseH[2], fuseE[2]};
          }
        }
        throw new Unsupported("Not Implemented");
      }
      return null;
    }

    String getVersion () throws Exception {
      if (protocol == Protocol.CATERINA) {
        byte[] data = sendCmd(new byte[]{'V'}, 2);
        if (data.length == 2) {
          return (char) data[0] + "." + (char) data[1];
        }
      } else if (protocol == Protocol.STKV1)  {
        byte[] major = sendCmd(new byte[]{0x41, (byte) 0x81, 0x20}, 1);
        if (major != null) {
          byte[] minor = sendCmd(new byte[]{0x41, (byte) 0x82, 0x20}, 1);
          return major[1] + "." + minor[1];
        }
      } else if (protocol == Protocol.STKV2)  {
        byte[] major = sendCmd(new byte[] {0x03, (byte) 0x91}, 3);
        if (major != null && major.length == 3) {
          byte[] minor = sendCmd(new byte[] {0x03, (byte) 0x92}, 3);
          if (minor != null && minor.length == 3) {
            return major[2] + "." + minor[2];
          }
        }
      }
      return null;
    }

    // Implement JSSCPort.RXEvent
    public void rxChar (byte cc) {
      if (DEBUG) {
        System.out.println("REC: " + toHex(cc) + (cc >= 0x20 && cc < 0x7F ?" '" + (char) cc + "'" : "") +
                           " - " + bout.size() + ", state = " + state);
      }
      synchronized (this) {
        timeout = 50;
      }
      if (protocol == Protocol.CATERINA) {
        bout.write(cc);
        synchronized (this) {
          len--;
        }
      } else if (protocol == Protocol.STKV1)  {
        bout.write(cc);
        // Use state machine to track STK500 protocol
        switch (state) {
          case 0:
            // Check for STK_INSYNC (0x14)
            state = cc == 0x14 ? (len > 0 ? 1 : 2) : 0;
            break;
          case 1:
            // Wait for all data bytes to be received
            if (bout.size() == len + 1) {
              state = 2;
            }
            break;
          case 2:
            // Check for STK_OK (0x10)
            if (DEBUG) {
              if (checksum != 0) {
                System.out.println("STK_OK not found on Read");
              }
            }
            state = cc == 0x10 ? 3 : 0;
            break;
        }
      } else if (protocol == Protocol.STKV2)  {
        checksum ^= cc;
        switch (state) {
          case 0:               // Wait for MESSAGE_START (0x1B)
            if (cc == 0x1B) {
              state = 1;
            }
            break;
          case 1:               // Wait for SEQUENCE_NUMBER (ignored)
            state = 2;
            break;
          case 2:               //  Wait for MESSAGE_SIZE MSB byte
            len = (int) cc << 8;
            state = 3;
            break;
          case 3:               //  Wait for MESSAGE_SIZE LSB byte
            len |= (int) cc & 0xFF;
            state = 4;
            break;
          case 4:               //  Wait for TOKEN (0x0E)
            state = cc == 0x0E ? 5 : 0;
            break;
          case 5:               //  Wait for <len> message bytes
            if (len-- > 0) {
              bout.write(cc);
            }
            if (len == 0) {
              state = 6;
            }
            break;
          case 6:               //  Wait for CHECKSUM (1 byte)
            if (DEBUG) {
              if (checksum != 0) {
                System.out.println("Checksum error on Read");
              }
            }
            state = checksum == 0 ? 7 : 0;
            break;
          case 7:               //  Meesage Received and Checksum is Good
            break;
        }
      }
    }
  }

  private ArduinoReader () {
    super("ArduinoReader");
    setBackground(Color.white);
    setLayout(new BorderLayout(1, 1));
    text = new JEditorPane();
    text.setFont(tFont);
    text.setContentType("text/plain");
    text.setEditable(false);
    JScrollPane scroll = new JScrollPane(text);
    add("Center", scroll);
    appendText("Ready\n");
    // Add menu bar and menus
    JMenuBar menuBar = new JMenuBar();
    // Add "Actions" Menu
    JMenu actions = new JMenu("Actions");
    JMenuItem mItem;
    actions.add(mItem = new JMenuItem("Get Version"));
    mItem.addActionListener(e -> {
      Thread doAction = new Thread(() -> {
        ArduinoBootDriver send = new ArduinoBootDriver(jPort);
        try {
          actions.setEnabled(false);
          if (send.sync()) {
            String version = send.getVersion();
            if (version != null) {
              appendText("Bootloader Version: " + version + "\n");
            } else {
              appendText("Unable to read Bootloader version\n");
            }
          } else {
            appendText("Unable to Sync Bootloader\n");
          }
        } catch (Unsupported ex) {
          appendText(ex.message + "\n");
        } catch (Exception ex) {
          ex.printStackTrace();
          appendText(ex.toString() + "\n");
        } finally {
          send.close();
          actions.setEnabled(true);
        }
      });
      doAction.start();
    });
    actions.add(mItem = new JMenuItem("Get Signature"));
    mItem.addActionListener(e -> {
      Thread doAction = new Thread(() -> {
        ArduinoBootDriver send = new ArduinoBootDriver(jPort);
        try {
          actions.setEnabled(false);
          if (send.sync()) {
            byte[] data = send.getSignature();
            if (data != null) {
              MCU device = devices.get(toHex(data[0]) + toHex(data[1]) + toHex(data[2]));
              appendText("Signature: " + toHex(data[0]) + " " + toHex(data[1]) + " " + toHex(data[2]) +
                        (device != null ? " - " + device.name : "") + "\n");
            } else {
              appendText(" Unable to read device signature\n");
            }
          } else {
            appendText("Unable to Sync Bootloader\n");
          }
        } catch (Unsupported ex) {
          appendText(ex.message + "\n");
        } catch (Exception ex) {
          ex.printStackTrace();
          appendText(ex.toString() + "\n");
        } finally {
          send.close();
          actions.setEnabled(true);
        }
      });
      doAction.start();
    });
    actions.add(mItem = new JMenuItem("Get Fuses"));
    mItem.addActionListener(e -> {
      Thread doAction = new Thread(() -> {
        ArduinoBootDriver send = new ArduinoBootDriver(jPort);
        try {
          actions.setEnabled(false);
          if (send.sync()) {
            byte[] data = send.getFuses();
            if (data != null) {
              appendText("Fuses - Low: " + toHex(data[0]) + ", High: " + toHex(data[1]) + ", Extd: " + toHex(data[2]) + "\n");
            } else {
              appendText("Unable to read Fuses\n");
            }
          } else {
            appendText("Unable to Sync Bootloader\n");
          }
        } catch (Unsupported ex) {
          appendText(ex.message + "\n");
        } catch (Exception ex) {
          ex.printStackTrace();
          appendText(ex.toString() + "\n");
        } finally {
          send.close();
          actions.setEnabled(true);
        }
      });
      doAction.start();
    });
    actions.add(mItem = new JMenuItem("Read Flash"));
    mItem.addActionListener(e -> {
      appendText("Read Flash\n");
      Thread doAction = new Thread(() -> {
        ArduinoBootDriver send = new ArduinoBootDriver(jPort);
        try {
          actions.setEnabled(false);
          if (send.sync()) {
            byte[] data = send.readFlash(0, 1024);
            if (data != null) {
              for (int ii = 0; ii < data.length; ii++) {
                if (ii % 32 == 0) {
                  appendText(toHex((byte) (ii >> 8)) + toHex((byte) (ii & 0xFF)) + ": ");
                }
                appendText(toHex(data[ii]));
                appendText((ii % 32 == 31 ? "\n" : " "));
              }
            } else {
              appendText("Read Error\n");
            }
          } else {
            appendText("Unable to Sync Bootloader\n");
          }
        } catch (Unsupported ex) {
          appendText(ex.message + "\n");
        } catch (Exception ex) {
          ex.printStackTrace();
          appendText(ex.toString() + "\n");
        } finally {
          send.close();
          actions.setEnabled(true);
        }
      });
      doAction.start();
    });
    actions.add(mItem = new JMenuItem("Read Bootloader"));
    mItem.addActionListener(e -> {
      appendText("Read Bootloader\n");
      Thread doAction = new Thread(() -> {
        ArduinoBootDriver send = new ArduinoBootDriver(jPort);
        try {
          actions.setEnabled(false);
          if (send.sync()) {
            byte[] data = send.getSignature();
            if (data != null) {
              MCU device = devices.get(toHex(data[0]) + toHex(data[1]) + toHex(data[2]));
              if (device != null) {
                byte[] fuses = send.getFuses();
                int bootSize = device.getBootSize(fuses) * 2;
                if (fuses == null) {
                  appendText("Unable to read fuses to determine bootloader size\n");
                } else {
                  int maxBoot = device.getMaxBootSize();
                  appendText("Bootloader using " + bootSize + " bytes of " + maxBoot + "\n");
                }
                int addr = device.flashSize - bootSize;
                data = send.readFlash(addr, bootSize);
                if (data != null) {
                  int off = 0;
                  if (skipFF) {
                    if (fuses == null) {
                      while (data[off] == (byte) 0xFF) {
                        off++;
                      }
                    }
                  }
                  if (off > 0) {
                    appendText("Found bootloader base by skipping 0xFF bytes\n");
                    off &= 0xFFF0;      // Align to multiple of 16 so printout looks pretty
                  }
                  StringBuilder ascii = new StringBuilder();
                  int checksum = 0;
                  for (int ii = off; ii < data.length; ii++) {
                    if (ii % 16 == 0) {
                      if (addr >= 0x10000) {
                        appendText(toHex24(addr + ii) + ": ");
                      } else {
                        appendText(toHex16(addr + ii) + ": ");
                      }
                      //appendText(toHex((byte) ((addr + ii) >> 8)) + toHex((byte) ((addr + ii) & 0xFF)) + ": ");
                    }
                    appendText(toHex(data[ii]));
                    checksum += (int) data[ii] & 0xFF;
                    if (data[ii] >= 0x20 && data[ii] < 0x7F) {
                      ascii.append((char) (0x20 + data[ii]));
                    } else {
                      ascii.append(' ');
                    }
                    if (ii % 16 == 15) {
                      appendText(" - ");
                      appendText(ascii.toString());
                      appendText("\n");
                      ascii = new StringBuilder();
                    } else {
                      appendText(" ");
                    }
                  }
                  appendText("Checksum: " + toHex(checksum) + " (" + checksum + ")\n");
                } else {
                  appendText("Read Error\n");
                }
              } else {
                appendText("Unknown device signature\n");
              }
            } else {
              appendText("Unable to read device signature\n");
            }
          } else {
            appendText("Unable to Sync Bootloader\n");
          }
        } catch (Unsupported ex) {
          appendText(ex.message + "\n");
        } catch (Exception ex) {
          ex.printStackTrace();
          appendText(ex.toString() + "\n");
        } finally {
          send.close();
          actions.setEnabled(true);
        }
      });
      doAction.start();
    });
    actions.add(mItem = new JMenuItem("DisAsm Bootloader"));
    mItem.addActionListener(e -> {
      appendText("Read Bootloader\n");
      Thread doAction = new Thread(() -> {
        ArduinoBootDriver send = new ArduinoBootDriver(jPort);
        try {
          actions.setEnabled(false);
          if (send.sync()) {
            byte[] data = send.getSignature();
            if (data != null) {
              MCU device = devices.get(toHex(data[0]) + toHex(data[1]) + toHex(data[2]));
              if (device != null) {
                byte[] fuses = send.getFuses();
                int bootSize = device.getBootSize(fuses) * 2;
                if (fuses == null) {
                  appendText("Unable to read fuses to determine bootloader size\n");
                } else {
                  int maxBoot = device.getMaxBootSize();
                  appendText("Bootloader using " + bootSize + " bytes of " + maxBoot + "\n");
                }
                int addr = device.flashSize - bootSize;
                data = send.readFlash(addr, bootSize);
                if (data != null) {
                  int off = 0;
                  if (skipFF) {
                    if (fuses == null) {
                      while (data[off] == (byte) 0xFF) {
                        off++;
                      }
                    }
                  }
                  if (off > 0) {
                    appendText("Found bootloader base by skipping 0xFF bytes\n");
                    off &= 0xFFF0;      // Align to multiple of 16 so printout looks pretty
                  }
                  AVRDisassembler disAsm = new AVRDisassembler();
                  disAsm.dAsm(data, off, addr + off, (data.length - off) / 2);
                  appendText(disAsm.getDisAsm());
                } else {
                  appendText("Read Error\n");
                }
              } else {
                appendText("Unknown device signature\n");
              }
            } else {
              appendText("Unable to read device signature\n");
            }
          } else {
            appendText("Unable to Sync Bootloader\n");
          }
        } catch (Unsupported ex) {
          appendText(ex.message + "\n");
        } catch (Exception ex) {
          ex.printStackTrace();
          appendText(ex.toString() + "\n");
        } finally {
          send.close();
          actions.setEnabled(true);
        }
      });
      doAction.start();
    });
    actions.addSeparator();
    actions.add(mItem = new JMenuItem("Clear Screen"));
    mItem.addActionListener(e -> text.setText(""));
    menuBar.add(actions);
    // Add Settings menu
    JMenu settings = new JMenu("Settings");
    menuBar.add(settings);
    JMenu tpiSettings = new JMenu("Serial Port");
    settings.add(tpiSettings);
    tpiSettings.setEnabled(false);
    Thread portThread = new Thread(() -> {
      // Add "Port" and "Baud" Menus to MenuBar
      try {
        jPort = new JSSCPort(prefs);
        tpiSettings.add(jPort.getPortMenu());
        tpiSettings.add(jPort.getBaudMenu());
      } catch (Exception ex) {
        ex.printStackTrace();
      } finally {
        tpiSettings.setEnabled(true);
      }
    });
    portThread.start();
    setJMenuBar(menuBar);
    // Add window close handler
    addWindowListener(new WindowAdapter() {
      public void windowClosing (WindowEvent ev) {
        System.exit(0);
      }
    });
    setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
    setSize(prefs.getInt("window.width", 800), prefs.getInt("window.height", 900));
    setLocation(prefs.getInt("window.x", 10), prefs.getInt("window.y", 10));
    // Track window resize/move events and save in prefs
    addComponentListener(new ComponentAdapter() {
      public void componentMoved (ComponentEvent ev)  {
        Rectangle bounds = ev.getComponent().getBounds();
        prefs.putInt("window.x", bounds.x);
        prefs.putInt("window.y", bounds.y);
      }
      public void componentResized (ComponentEvent ev)  {
        Rectangle bounds = ev.getComponent().getBounds();
        prefs.putInt("window.width", bounds.width);
        prefs.putInt("window.height", bounds.height);
      }
    });
    setVisible(true);
  }

  private String toHex16 (int val) {
    return toHex((byte) (val >> 8)) + toHex((byte) (val & 0xFF));
  }


  private String toHex24 (int val) {
    return toHex((byte) (val >> 16)) + toHex((byte) (val >> 8)) + toHex((byte) (val & 0xFF));
  }

  private void appendText (String txt) {
    Document doc = text.getDocument();
    try {
      doc.insertString(doc.getLength(), txt, null);
      repaint();
    } catch (Exception ex) {
      ex.printStackTrace();
    }
  }

  private static String toHex (int val) {
    return toHex((byte) (val >> 8)) + toHex((byte) (val & 0xFF));
  }

  private static String toHex (byte data) {
    int val = data & 0xFF;
    return (val < 0x10 ? "0" : "") + Integer.toHexString(val).toUpperCase();
  }

  public static void main (String[] args) {
    java.awt.EventQueue.invokeLater(ArduinoReader::new);
  }
}
