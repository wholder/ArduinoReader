# ArduinoReader

ArduinoReader is a utility I wrote to investigate and explore Arduino BootLoaders by implementing several of the protocols used to talk to the BootLoader installed on an Arduino Board.  It currently supports three different protocols, STK500V1, STK500V2 and one based on a subset of the protocol described in AVR109.  STK500V1 is used to talk to BootLoaders on Arduino Boards based on the ATMega328(P), and several other AVR MCU's that have 32K, or less of Flash Memory.  STK500V2 is used to talk to the BootLoader on the Arduino MEGA series and the AVR109-based protocol is used to talk to Arduino Boards based on the ATMega32U4, such as the Leonardo.

If you just want to try out the program, you don't need to download and compile the source code, as I try to maintain a pre-built, executable JAR file in the [out/artifacts/ArduinoReader_jar](https://github.com/wholder/ArduinoReader/tree/master/out/artifacts/ArduinoReader_jar) folder from which you can download and run ArduinoReader as long as you have Java installed on your system.  On a Mac, simply double click the ArduinoReader.jar file to run it once you've downloaded it, although you'll probably have to right click and select "Open" the  first time you run ArduinoReader due to the Mac OS X security check and the fact that I'm currently unable to digitally sign the JAR file.  You should also be able to double click and run using Windows, but some versions of Windows may require you to [enable this ability](https://www.addictivetips.com/windows-tips/run-a-jar-file-on-windows/) first.  You'll need to give the .jar file RUN permission to execute on Linux.

## Requirements

I suggest using Java 8 JRE or JDK, or later for ArduinoReader, but the code also seems to run under the OpenJDK Java on Linux.  Note: I wrote ArduinoReader on a Mac Pro using the _Community_ version of [IntelliJ IDEA from JetBrains](https://www.jetbrains.com/idea/) and OS X is the only environment where I have extensively tested and used ArduinoReader.  Feel free to report any issues you discover.  I'll do my best, when time permits, to investigate them, but I cannot guarantee fixes, timely or otherwise.

### How it Works

**Note: This code is  still in development and has several features that are currently unfinished, or unpolished, or both.**

ArduinoReader uses Java Simple Serial Connector 2.8.0 to talk to the various Arduno Boards.  The code tries to automate the process of detecting which protocol is needed to talk to a give Arduino Boards but, currently you first need to use the **Settings** menu to select a port and baud rate.  Most recent Arduinos use a baud rate of 115200, but you may need to select 57600, or lower to talk to older boards.

Then, you can use the **Actions** menu to read the BootLoader's version number, the MCU's signature and fuse bytes (not supported by all BootLoaders) as well as read out the Application area of Flash Memory (prints until it detects 16 `0xFF` bytes in a row), or the section of Memory that can contain the BootLoader.  Ideally, ArduinoReader tries to interpret the fuse bits to determine the exact potion of Flash Memory used by the BootLoader, but this is not possible for BootLoaders that do not implement a way to read the fuses, such as Optiboot.  In these cases, ArduinoReader will try skipping over unprogrammed Flash Memory bytes (`0xFF`) to determine the base of the BootLoader.  Or, as a fallback, it will dump the entire range of Flash that could contain a BootLoader.  _There is also a command to display a disassembly of the BootLoader code, but this feature is still under development and some aspects of the disassembly may be incorrect._

### Vanishing Serial Ports on MacOs

I'm not sure if this problem is caused by JSSC, or MacOs but, from time to time, a USB serial device, such as an Arduino Board will stop showing up in the Ports menu.  When this happens, the only cure I've found is to shutdown and restart the Mac, at which point the vanished ports should reappear.  If you know anything about this phenomenon and how to fix it, please provide details in the "Issues" section of this project.

### On the "To Do" List

  + Add an interface to allow manual selection of a range of Flash Memory to dump to the sceen.
  + Add an option to save a range to Flash Memory to an Intel Hex file.
  + Add an option to checksum the program area of Flash Memory and print it to the display.
  
### Credit and Thanks

This project would have been much harder to write without help from the following open source projects, or freely available software.

- [Java Simple Serial Connector 2.8.0](https://github.com/scream3r/java-simple-serial-connector) - JSSC is used to communicate with the Arduino-based programmer
- [IntelliJ IDEA from JetBrains](https://www.jetbrains.com/idea/) (my favorite development environment for Java coding. Thanks JetBrains!)
- Bill Westfield or his help explaining the nuances of bootloaders and Optiboot.

### MIT License

Copyright 2014-2018 Wayne Holder

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.