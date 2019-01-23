import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.prefs.Preferences;
import java.util.regex.Pattern;

import jssc.*;

import javax.swing.*;
import javax.swing.event.MenuEvent;
import javax.swing.event.MenuListener;

/*
 * Encapsulates JSSC functionality into an easy to use class
 * See: https://code.google.com/p/java-simple-serial-connector/
 * And: https://github.com/scream3r/java-simple-serial-connector/releases
 *
 * Native drivers installed at path: ~/.jssc/mac_os_x/
 *    libjSSC-2.6_x86_64.jnilib
 *    libjSSC-2.8_x86.jnilib
 *    libjSSC-2.8_x86_64.jnilib
 *
 *  Author: Wayne Holder, 2015-2017 (first version 10/30/2015)
 */

public class JSSCPort implements SerialPortEventListener {
  private static final Map<String,Integer> baudRates = new LinkedHashMap<>();
  private ArrayBlockingQueue<Integer>  queue = new ArrayBlockingQueue<>(1000);
  private static Pattern      macPat = Pattern.compile("cu.");
  private static final int    dataBits = 8, stopBits = SerialPort.STOPBITS_1, parity = SerialPort.PARITY_NONE;
  private static final int    flowCtrl = SerialPort.FLOWCONTROL_NONE;
  private static final int    eventMasks = 0;   // See: SerialPort.MASK_RXCHAR, MASK_TXEMPTY, MASK_CTS, MASK_DSR
  private Preferences         prefs;
  private String              portName;
  private int                 baudRate;
  private SerialPort          serialPort;
  private boolean             hasListener;
  private List<RXEvent>       rxHandlers = new ArrayList<>();

  interface RXEvent {
    void rxChar (byte cc);
  }

  static {
    baudRates.put("110",    SerialPort.BAUDRATE_110);
    baudRates.put("300",    SerialPort.BAUDRATE_300);
    baudRates.put("600",    SerialPort.BAUDRATE_600);
    baudRates.put("1200",   SerialPort.BAUDRATE_1200);
    baudRates.put("2400",   2400);    // Note: constant missing in JSSC
    baudRates.put("4800",   SerialPort.BAUDRATE_4800);
    baudRates.put("9600",   SerialPort.BAUDRATE_9600);
    baudRates.put("14400",  SerialPort.BAUDRATE_14400);
    baudRates.put("19200",  SerialPort.BAUDRATE_19200);
    baudRates.put("38400",  SerialPort.BAUDRATE_38400);
    baudRates.put("57600",  SerialPort.BAUDRATE_57600);
    baudRates.put("115200", SerialPort.BAUDRATE_115200);
    baudRates.put("128000", SerialPort.BAUDRATE_128000);
    baudRates.put("256000", SerialPort.BAUDRATE_256000);
  }

  public JSSCPort (Preferences prefs) throws SerialPortException {
    this.prefs = prefs;
    // Determine OS Type
    switch (SerialNativeInterface.getOsType()) {
      case SerialNativeInterface.OS_LINUX:
        macPat = Pattern.compile("(ttyS|ttyUSB|ttyACM|ttyAMA|rfcomm)[0-9]{1,3}");
        break;
      case SerialNativeInterface.OS_MAC_OS_X:
        break;
      case SerialNativeInterface.OS_WINDOWS:
        macPat = Pattern.compile("");
        break;
      default:
        macPat = Pattern.compile("tty.*");
        break;
    }
    portName = prefs.get("serial.port", null);
    baudRate = prefs.getInt("serial.baud", 115200);
  }

  public boolean touch1200 () throws SerialPortException {
    try {
      if (serialPort != null) {
        if (serialPort.isOpened()) {
          close();
        }
      }
      if (portName != null) {
        serialPort = new SerialPort(portName);
        serialPort.openPort();
        //System.out.println("JSSCPort.touch1200()");
        serialPort.setParams(1200, dataBits, stopBits, parity, false, false);  // baud, 8 bits, 1 stop bit, no parity
        serialPort.setDTR(false);
        close();
        try {
          Thread.sleep(400);
        } catch (InterruptedException ex) {
          ex.printStackTrace();
        }
        for (int ii = 0; ii < 10; ii++) {
          for (String pName : SerialPortList.getPortNames(macPat)) {
            if (portName.equals(pName)) {
              return true;
            }
          }
          try {
            Thread.sleep(100);
          } catch (InterruptedException ex) {
            ex.printStackTrace();
          }
        }
      }
    } finally {
      if (serialPort != null && serialPort.isOpened()) {
        close();
      }
    }
    return false;
  }

  public boolean open (RXEvent handler) throws SerialPortException {
    if (serialPort != null) {
      if (serialPort.isOpened()) {
        close();
      }
    }
    if (portName != null) {
      serialPort = new SerialPort(portName);
      serialPort.openPort();
      //System.out.println("JSSCPort.open()");
      serialPort.setParams(baudRate, dataBits, stopBits, parity, false, false);  // baud, 8 bits, 1 stop bit, no parity
      serialPort.setEventsMask(eventMasks);
      serialPort.setFlowControlMode(flowCtrl);
      serialPort.addEventListener(JSSCPort.this);
      hasListener = true;
      setRXHandler(handler);
      return true;
    }
    return false;
  }

  public void close () {
    if (serialPort != null && serialPort.isOpened()) {
      try {
        synchronized (this) {
          rxHandlers.clear();
        }
        if (hasListener) {
          serialPort.removeEventListener();
          hasListener = false;
        }
        serialPort.closePort();
        serialPort = null;
        //System.out.println("JSSCPort.close()");
      } catch (SerialPortException ex) {
        ex.printStackTrace();
      }
    }
  }

  public void serialEvent (SerialPortEvent se) {
    try {
      if (se.getEventType() == SerialPortEvent.RXCHAR) {
        int rxCount = se.getEventValue();
        byte[] inChars = serialPort.readBytes(rxCount);
        if (rxHandlers.size() > 0) {
          for (byte cc : inChars) {
            for (RXEvent handler : rxHandlers) {
              handler.rxChar(cc);
            }
          }
        } else {
          for (byte cc : inChars) {
            if (queue.remainingCapacity() > 0) {
              queue.add((int) cc);
            }
          }
        }
      }
    } catch (Exception ex) {
      ex.printStackTrace();
    }
  }

  public void setRXHandler (RXEvent handler) {
    synchronized (this) {
      rxHandlers.add(handler);
    }
  }

  public void sendByte (byte data) throws SerialPortException {
    serialPort.writeByte(data);
  }

  public void sendBytes (byte[] data) throws SerialPortException {
    serialPort.writeBytes(data);
  }

  public void sendString (String data) throws SerialPortException {
    serialPort.writeString(data);
  }

  // Note: true set TTL level to HIGH (DTR off)
  public void setDTR (boolean state) {
    try {
      serialPort.setDTR(!state);
    } catch (Exception ex) {
      ex.printStackTrace();
    }
  }

  // Note: true set TTL level to HIGH (RTS off)
  public void setRTS (boolean state) {
    try {
      serialPort.setRTS(!state);
    } catch (Exception ex) {
      ex.printStackTrace();
    }
  }

  public void sendBreak (int duration) {
    try {
      serialPort.sendBreak(duration);
    } catch (Exception ex) {
      ex.printStackTrace();
    }
  }

  public boolean getCTS () {
    try {
      return !serialPort.isCTS();
    } catch (Exception ex) {
      ex.printStackTrace();
    }
    return true;
  }

  public boolean getDSR () {
    try {
      return !serialPort.isDSR();
    } catch (Exception ex) {
      ex.printStackTrace();
    }
    return true;
  }

  public byte getChar () {
    int val = 0;
    if (rxHandlers.size() > 0) {
      throw new IllegalStateException("Can't call when RXEvent is defined");
    } else {
      try {
        val = queue.take();
      } catch (InterruptedException ex) {
        ex.printStackTrace(System.out);
      }
    }
    return (byte) val;
  }

  public JMenu getPortMenu () {
    JMenu menu = new JMenu("Port");
    menu.addMenuListener(new MenuListener() {
      @Override
      public void menuSelected (MenuEvent e) {
        // Populate menu on demand
        menu.removeAll();
        ButtonGroup group = new ButtonGroup();
        for (String pName : SerialPortList.getPortNames(macPat)) {
          JRadioButtonMenuItem item = new JRadioButtonMenuItem(pName, pName.equals(portName));
          menu.setVisible(true);
          menu.add(item);
          group.add(item);
          item.addActionListener((ev) -> {
            portName = ev.getActionCommand();
            prefs.put("serial.port", portName);
          });
        }
      }

      @Override
      public void menuDeselected (MenuEvent e) { }

      @Override
      public void menuCanceled (MenuEvent e) { }
    });
    return menu;
  }

  public JMenu getBaudMenu () {
    JMenu menu = new JMenu("Baud Rate");
    ButtonGroup group = new ButtonGroup();
    for (String bRate : baudRates.keySet()) {
      int rate = baudRates.get(bRate);
      JRadioButtonMenuItem item = new JRadioButtonMenuItem(bRate, baudRate == rate);
      menu.add(item);
      menu.setVisible(true);
      group.add(item);
      item.addActionListener((ev) -> {
        String cmd = ev.getActionCommand();
        prefs.putInt("serial.baud", baudRate = Integer.parseInt(cmd));
      });
    }
    return menu;
  }
}
