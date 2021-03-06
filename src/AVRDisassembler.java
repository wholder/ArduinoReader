import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.StringTokenizer;

@SuppressWarnings("StatementWithEmptyBody")
public class AVRDisassembler {
  private ByteArrayOutputStream bout = new ByteArrayOutputStream();
  private PrintStream           pOut = new PrintStream(bout);
  private int                   cursor;

  /**
   * Implements a basic disassembler for the AVR Instruction Set
   * Note: this dissaambler was written quickly and crudely so there may be errors, or omissions in its output
   *
   * @param flash byte[] array with AVR code to disassemble
   * @param offset offset into flash[] array
   * @param addr base address for disassembly
   * @param count number of 16 bit words to disassemble
   */
  void dAsm (byte[] flash, int offset, int addr, int count) {
    try {
      for (int ii = 0; ii < count; ii++) {
        cursor = 0;
        boolean skipWord = false;
        int idx = ii * 2;
        int word2 = 0;
        // 16 Bit Opcode is MSB:LSB Order
        int opcode = getFlashWord(flash, offset + idx);
        printAddr(addr + idx);
        printCmd();
        printHex16(opcode);
        tabTo(14);
        if ((opcode & ~0x1F0) == 0x9000) {                        // lds (4 byte instruction)
          printInst("lds");
          printDstReg((opcode & 0x1F0) >> 4);
          print(",0x");
          word2 = getFlashWord(flash, offset + idx + 2);
          printHex16(word2);
          skipWord = true;
        } else if ((opcode & ~0x1F0) == 0x9200) {                 // sts (4 byte instruction)
          printInst("sts");
          print("0x");
          word2 = getFlashWord(flash, offset + idx + 2);
          printHex16(word2);
          printSrcReg((opcode & 0x1F0) >> 4);
          skipWord = true;
        } else if ((opcode & 0x0FE0E) == 0x940C) {                // jmp (4 byte instruction)
          printInst("jmp");
          print("0x");
          word2 = getFlashWord(flash, offset + idx + 2);
          // 22 bit address
          int add22 = (opcode & 0x1F0) << 13;
          add22 += (opcode & 1) << 16;
          add22 += word2;
          printAddr(add22 * 2);
          skipWord = true;
        } else if ((opcode & 0x0FE0E) == 0x940E) {                // call (4 byte instruction)
          printInst("call");
          print("0x");
          word2 = getFlashWord(flash, offset + idx + 2);
          // 22 bit address
          int add22 = (opcode & 0x1F0) << 13;
          add22 += (opcode & 1) << 16;
          add22 += word2;
          printAddr(add22 * 2);
          skipWord = true;
        } else {
          dAsm2Byte(addr + idx, opcode);
        }
        if (skipWord) {
          // Print 2nd line to show extra word used by 2 word instructions
          println();
          if (addr + idx + 2 > 0x10000) {
            printAddr(addr + idx + 2);
          } else {
            printHex16(addr + idx + 2);
          }
          print(":");
          tabTo(8);
          printHex16(word2);
          ii++;
        }
        println();
      }
    } catch (ArrayIndexOutOfBoundsException ex) {
      print("<end of data>");
    }
  }

  private int getFlashWord (byte[] flash, int idx) {
    return (((int) flash[idx + 1] & 0xFF) << 8) + ((int) flash[idx] & 0xFF);
  }

  /*
   * Specials case instructions: implement?
   *    ELPM  95D8
   *    LPM   95C8
   *
   * Special case, implied operand display?
   *    SPM   Z+,r1:r0
   */

  // Disassemble 2 byte Instructions
  private void dAsm2Byte (int addr, int opcode) {
    if (dAsmNoArgs(opcode)) {                                         // clc, clh, etc.
    } else if (dAsmLogic(opcode)) {                                   //
      printDstReg((opcode & 0x1F0) >> 4);
    } else if (dAsmXYZStore(opcode)) {                                //
      printSrcReg((opcode & 0x1F0) >> 4);
    } else if (dAsmBranch(opcode)) {                                  // Branch instruction
      if ((opcode & 0x200) != 0) {
        int delta = (((opcode | 0xFC00) >> 3) + 1) * 2;               // Negative offset
        delta |= 0xFFFFFC00;                                          // extend sign bit
        printAddr(addr + delta);
      } else {
        int delta = (((opcode & 0x3F8) >> 3) + 1) * 2;                // Positive offset
        printAddr(addr + delta);
      }
    } else if (dAsmArith(opcode)) {
      printDstReg((opcode & 0x1F0) >> 4);
      printSrcReg(((opcode & 0x200) >> 5) + (opcode & 0x0F));
    } else if (dAsmBitOps(opcode)) {                                  //
      printDstReg((opcode & 0x1F0) >> 4);
      print(",");
      printDec(opcode & 0x07);
    } else if (dAsmLddYZQ(opcode)) {                                  // ldd rn,Y+q, ldd rn,Z+q
      // Handled in function
    } else if (dAsmStdYZQ(opcode)) {                                  // std Y+q,rn, std Z+q,rn
      // Handled in function
    } else if (dAsmXYZLoad(opcode)) {                                 //
      // Handled in function
    } else if (dAsmRelCallJmp(opcode)) {
      if ((opcode & 0x800) != 0) {
        int delta = (((opcode | 0xF000)) + 1) * 2;                    // Negative offset
        delta |= 0xFFFFF000;                                          // extend sign bit
        printAddr(addr + delta);
      } else {
        int delta = (((opcode & 0xFFF)) + 1) * 2;                     // Positive offset
        printAddr(addr + delta);
      }
    } else if ((opcode & ~0x7FF) == 0xB000) {                          // in rn,0xnn
      printInst("in");
      printDstReg((opcode & 0x1F0) >> 4);
      print(",0x");
      printHex8(((opcode & 0x600) >> 5) + (opcode & 0x0F));
    } else if ((opcode & ~0x7FF) == 0xB800) {                           // out 0xnn,rn
      printInst("out");
      print("0x");
      printHex8(((opcode & 0x600) >> 5) + (opcode & 0x0F));
      printSrcReg((opcode & 0x1F0) >> 4);
    } else if (dAsmByteImd(opcode)) {                                   // cpi, sbci, subi, ori, andi or ldi
      printDstReg(((opcode & 0xF0) >> 4) + 16);
      print(",0x");
      printHex8(((opcode & 0xF00) >> 4) + (opcode & 0x0F));
    } else if ((opcode & 0xF800) == 0xA000) {                           // lds
      printInst("lds");
      printDstReg(((opcode & 0xF0) >> 4) + 16);
      print(",0x");
      printHex8(((opcode & 0x700) >> 4) + (opcode & 0x0F) + 0x40);
    } else if ((opcode & 0xF800) == 0xA800) {                           // sts
      printInst("sts");
      print("0x");
      printHex8(((opcode & 0x700) >> 4) + (opcode & 0x0F) + 0x40);
      printSrcReg(((opcode & 0xF0) >> 4) + 16);
    } else if (dAsmSetClr(opcode)) {                                    // bclr or bset
      print(" ");
      printDec((opcode & 0x70) >> 4);
    } else if (dAsmBitOps2(opcode)) {                                   // cbi, sbi, sbic or sbis
      print("0x");
      printHex8((opcode & 0xF8) >> 3);
      print(",");
      printDec( opcode & 0x07);
    } else if (dAsmWordImd(opcode)) {                                   // adiw or sbiw
      printDstPair(((opcode & 0x30) >> 4) * 2 + 24);
      print(",0x");
      printHex8(((opcode & 0xC0) >> 4) + (opcode & 0x0F));
    } else if (dAsmMul(opcode)) {                                       // mulsu, fmul, fmuls or fmulsu
      printDstReg(((opcode & 7) >> 4) + 16);
      printSrcReg((opcode & 0x07) + 16);
    } else if ((opcode & ~0xFF) == 0x0100) {                            // movw r17:16,r1:r0
      printInst("movw");
      printDstPair(((opcode & 0xF0) >> 4) * 2);
      print(",");
      printSrcPair((opcode & 0x0F) * 2);
    } else if ((opcode & ~0xFF) == 0x0200) {                            // muls r21,r20
      printInst("muls");
      printDstReg(((opcode & 0xF0) >> 4) + 16);
      printSrcReg((opcode & 0x0F) + 16);
    } else {
      print("unknown");
    }
  }

  // Print functions that track cursor position (to support tabbing)

  private void printCmd () {
    print(":");
    tabTo(8);
  }

  private void print (String txt) {
    cursor += txt.length();
    pOut.print(txt);
  }

  private void println () {
    pOut.println();
    cursor = 0;
  }

  private void tabTo (int pos) {
    while (cursor < pos) {
      pOut.print(" ");
      cursor++;
    }
  }

  private void printHex8 (int val) {
    if (val < 0x10) {
      pOut.print("0");
    }
    pOut.print(Integer.toHexString(val).toUpperCase());
    cursor += 2;
  }

  private void printAddr (int val) {
    if (val >= 0x10000) {
      printHex24(val);
    } else {
      printHex16(val);
    }
  }

  private void printHex16 (int val) {
    printHex8((val >> 8) & 0xFF);
    printHex8(val & 0xFF);
  }

  private void printHex24 (int val) {
    printHex8((val >> 16) & 0xFF);
    printHex8((val >> 8) & 0xFF);
    printHex8(val & 0xFF);
  }

  private void printDec (int val) {
    pOut.print(val);
    if (val < 10) {
      cursor++;
    } else if (val < 100) {
      cursor += 2;
    } else {
      cursor += 3;
    }
  }

  private void printInst (String str) {
    print(str);
    tabTo(20);
  }

  private void printDstReg (int reg) {
    print("r");
    printDec(reg);
  }

  private void printSrcReg (int reg) {
    print(",r");
    printDec(reg);
  }

  private void printDstPair (int reg) {
    print("r");
    printDec(reg + 1);
    print(":r");
    printDec(reg);
  }

  private void printSrcPair (int reg) {
    print("r");
    printDec(reg + 1);
    print(":r");
    printDec(reg);
  }

  private boolean dAsmNoArgs (int opcode) {
    switch (opcode) {
      case 0x0000: print("nop");     return true;                 // 0000 0000 0000 0000
      case 0x9408: print("sec");     return true;                 // 1001 0100 0000 1000
      case 0x9409: print("ijmp");    return true;                 // 1001 0100 0000 1001
      case 0x9418: print("sez");     return true;                 // 1001 0100 0001 1000
      case 0x9419: print("eijmp");   return true;                 // 1001 0100 0001 1001
      case 0x9428: print("sen");     return true;                 // 1001 0100 0010 1000
      case 0x9438: print("sev");     return true;                 // 1001 0100 0011 1000
      case 0x9448: print("ses");     return true;                 // 1001 0100 0100 1000
      case 0x9458: print("seh");     return true;                 // 1001 0100 0101 1000
      case 0x9468: print("set");     return true;                 // 1001 0100 0110 1000
      case 0x9478: print("sei");     return true;                 // 1001 0100 0111 1000
      case 0x9488: print("clc");     return true;                 // 1001 0100 1000 1000
      case 0x9498: print("clz");     return true;                 // 1001 0100 1001 1000
      case 0x94A8: print("cln");     return true;                 // 1001 0100 1010 1000
      case 0x94B8: print("clv");     return true;                 // 1001 0100 1011 1000
      case 0x94C8: print("cls");     return true;                 // 1001 0100 1100 1000
      case 0x94D8: print("clh");     return true;                 // 1001 0100 1101 1000
      case 0x94E8: print("clt");     return true;                 // 1001 0100 1110 1000
      case 0x94F8: print("cli");     return true;                 // 1001 0100 1111 1000
      case 0x9508: print("ret");     return true;                 // 1001 0101 0000 1000
      case 0x9509: print("icall");   return true;                 // 1001 0101 0000 1001
      case 0x9518: print("reti");    return true;                 // 1001 0101 0001 1000
      case 0x9519: print("eicall");  return true;                 // 1001 0101 0001 1001
      case 0x9588: print("sleep");   return true;                 // 1001 0101 1000 1000
      case 0x9598: print("break");   return true;                 // 1001 0101 1001 1000
      case 0x95A8: print("wdr");     return true;                 // 1001 0101 1010 1000
      case 0x95C8: print("lpm");     return true;                 // 1001 0101 1100 1000
      case 0x95D8: print("elpm");    return true;                 // 1001 0101 1101 1000
      case 0x95E8: print("spm");     return true;                 // 1001 0101 1110 1000
      case 0x95F8: print("spm");     return true;                 // 1001 0101 1111 1000
    }
    return false;
  }

  private boolean dAsmSetClr (int opcode) {
    switch (opcode & 0xFF8F) {
      case 0x9408: printInst("bset"); return true;                // 1001 0100 0sss 1000
      case 0x9488: printInst("bclr"); return true;                // 1001 0100 1sss 1000
    }
    return false;
  }

  private boolean dAsmLogic (int opcode) {
    switch (opcode & 0xFE0F) {
      case 0x900F: printInst("pop");   return true;               // 1001 000d dddd 1111
      case 0x920F: printInst("push");  return true;               // 1001 001d dddd 1111
      case 0x9400: printInst("com");   return true;               // 1001 010d dddd 0000
      case 0x9401: printInst("neg");   return true;               // 1001 010d dddd 0001
      case 0x9402: printInst("swap");  return true;               // 1001 010d dddd 0010
      case 0x9403: printInst("inc");   return true;               // 1001 010d dddd 0011
      case 0x9405: printInst("asr");   return true;               // 1001 010d dddd 0101
      case 0x9406: printInst("lsr");   return true;               // 1001 010d dddd 0110
      case 0x9407: printInst("ror");   return true;               // 1001 010d dddd 0111
      case 0x940A: printInst("dec");   return true;               // 1001 010d dddd 1010
    }
    return false;
  }

  private boolean dAsmXYZStore (int opcode) {
    switch (opcode & 0xFE0F) {
      case 0x8200: printInst("st");  print("Z");  return true;    // 1000 001r rrrr 0000
      case 0x8208: printInst("st");  print("Y");  return true;    // 1000 001r rrrr 1000
      case 0x9201: printInst("st");  print("Z+"); return true;    // 1001 001r rrrr 0001
      case 0x9202: printInst("st");  print("-Z"); return true;    // 1001 001r rrrr 0010
      case 0x9204: printInst("xch"); print("Z");  return true;    // 1001 001r rrrr 0100
      case 0x9205: printInst("las"); print("Z");  return true;    // 1001 001r rrrr 0101
      case 0x9206: printInst("lac"); print("Z");  return true;    // 1001 001r rrrr 0110
      case 0x9207: printInst("lat"); print("Z");  return true;    // 1001 001r rrrr 0111
      case 0x9209: printInst("st");  print("Y+"); return true;    // 1001 001r rrrr 1001
      case 0x920A: printInst("st");  print("-Y"); return true;    // 1001 001r rrrr 1010
      case 0x920C: printInst("st");  print("X");  return true;    // 1001 001r rrrr 1100
      case 0x920D: printInst("st");  print("X+"); return true;    // 1001 001r rrrr 1101
      case 0x920E: printInst("st");  print("-X"); return true;    // 1001 001r rrrr 1110
    }
    return false;
  }

  private boolean dAsmXYZLoad (int opcode) {
    String src;
    switch (opcode & 0xFE0F) {
      case 0x8000: printInst("ld");   src = "Z";  break;          // 1000 000d dddd 0000
      case 0x8008: printInst("ld");   src = "Y";  break;          // 1000 000d dddd 1000
      case 0x9001: printInst("ld");   src = "Z+"; break;          // 1001 000d dddd 0001
      case 0x9002: printInst("ld");   src = "-Z"; break;          // 1001 000d dddd 0010
      case 0x9004: printInst("lpm");  src = "Z";  break;          // 1001 000d dddd 0100
      case 0x9005: printInst("lpm");  src = "Z+"; break;          // 1001 000d dddd 0101
      case 0x9006: printInst("elpm"); src = "Z";  break;          // 1001 000d dddd 0110
      case 0x9007: printInst("elpm"); src = "Z+"; break;          // 1001 000d dddd 0111
      case 0x9009: printInst("ld");   src = "Y+"; break;          // 1001 000d dddd 1001
      case 0x900A: printInst("ld");   src = "-Y"; break;          // 1001 000d dddd 1010
      case 0x900C: printInst("ld");   src = "X";  break;          // 1001 000d dddd 1100
      case 0x900D: printInst("ld");   src = "X+"; break;          // 1001 000d dddd 1101
      case 0x900E: printInst("ld");   src = "-X"; break;          // 1001 000d dddd 1110
      default: return false;
    }
    printDstReg((opcode & 0x1F0) >> 4);
    print(",");
    print(src);
    return true;
  }

  private boolean dAsmLddYZQ (int opcode) {
    String src;
    switch (opcode & 0xD208) {
      case 0x8000: printInst("ldd");  src = "Z+";  break;         // 1000 000d dddd 0000
      case 0x8008: printInst("ldd");  src = "Y+";  break;         // 1000 000d dddd 1000
      default: return false;
    }
    int qq = ((opcode & 0x2000) >> 8) + ((opcode & 0x0C00) >> 7) + (opcode & 0x07);
    printDstReg((opcode & 0x1F0) >> 4);
    print(",");
    print(src);
    printDec(qq);
    return true;
  }

  private boolean dAsmStdYZQ (int opcode) {
    switch (opcode & 0xD208) {
      case 0x8200: printInst("std");  print("Z+");  break;         // 1000 001r rrrr 0000
      case 0x8208: printInst("std");  print("Y+");  break;         // 1000 001r rrrr 1000
      default: return false;
    }
    int qq = ((opcode & 0x2000) >> 8) + ((opcode & 0x0C00) >> 7) + (opcode & 0x07);
    printDec(qq);
    print(",");
    printDstReg((opcode & 0x1F0) >> 4);
    return true;
  }

  private boolean dAsmRelCallJmp (int opcode) {
    switch (opcode & 0xF000) {
      case 0xC000: printInst("rjmp");  return true;               // 1100 kkkk kkkk kkkk
      case 0xD000: printInst("rcall"); return true;               // 1101 kkkk kkkk kkkk
    }
    return false;
  }

  private boolean dAsmBitOps2  (int opcode) {
    switch (opcode & 0xFF00) {
      case 0x9800: printInst("cbi");   return true;               // 1001 1000 AAAA Abbb
      case 0x9900: printInst("sbic");  return true;               // 1001 1001 AAAA Abbb
      case 0x9A00: printInst("sbi");   return true;               // 1001 1010 AAAA Abbb
      case 0x9B00: printInst("sbis");  return true;               // 1001 1011 AAAA Abbb
    }
    return false;
  }

  private boolean dAsmByteImd (int opcode) {
    switch (opcode & 0xF000) {
      case 0x3000: printInst("cpi");   return true;               // 0011 KKKK dddd KKKK
      case 0x4000: printInst("sbci");  return true;               // 0100 KKKK dddd KKKK
      case 0x5000: printInst("subi");  return true;               // 0101 KKKK dddd KKKK
      case 0x6000: printInst("ori");   return true;               // 0110 KKKK dddd KKKK
      case 0x7000: printInst("andi");  return true;               // 0111 KKKK dddd KKKK
      case 0xE000: printInst("ldi");   return true;               // 1110 KKKK dddd KKKK
    }
    return false;
  }

  private boolean dAsmArith (int opcode) {
    switch (opcode & 0xFC00) {
      case 0x0400: printInst("cpc");   return true;               // 0000 01rd dddd rrrr
      case 0x0800: printInst("sbc");   return true;               // 0000 10rd dddd rrrr
      case 0x0C00: printInst("add");   return true;               // 0000 11rd dddd rrrr
      case 0x1000: printInst("cpse");  return true;               // 0001 00rd dddd rrrr
      case 0x1400: printInst("cp ");   return true;               // 0001 01rd dddd rrrr
      case 0x1800: printInst("sub");   return true;               // 0001 10rd dddd rrrr
      case 0x1C00: printInst("adc");   return true;               // 0001 11rd dddd rrrr
      case 0x2000: printInst("and");   return true;               // 0010 00rd dddd rrrr
      case 0x2400: printInst("eor");   return true;               // 0010 01rd dddd rrrr
      case 0x2800: printInst("or");    return true;               // 0010 10rd dddd rrrr
      case 0x2C00: printInst("mov");   return true;               // 0010 11rd dddd rrrr
      case 0x9C00: printInst("mul");   return true;               // 1001 11rd dddd rrrr
    }
    return false;
  }

  private boolean dAsmWordImd (int opcode) {
    switch (opcode & 0xFF00) {
      case 0x9600: printInst("adiw"); return true;               // 1001 0110 KKdd KKKK
      case 0x9700: printInst("sbiw"); return true;               // 1001 0111 KKdd KKKK
    }
    return false;
  }

  private boolean dAsmBitOps (int opcode) {
    switch (opcode & 0xFE08) {
      case 0xF800: printInst("bld");   return true;             // 1111 100d dddd 0bbb
      case 0xFA00: printInst("bst");   return true;             // 1111 101d dddd 0bbb
      case 0xFC00: printInst("sbrc");  return true;             // 1111 110r rrrr 0bbb
      case 0xFE00: printInst("sbrs");  return true;             // 1111 111r rrrr 0bbb
    }
    return false;
  }

  private boolean dAsmBranch (int opcode) {
    switch (opcode & 0xFC07) {
      case 0xF000: printInst("brcs"); return true;              // 1111 00kk kkkk k000
      case 0xF001: printInst("breq"); return true;              // 1111 00kk kkkk k001
      case 0xF002: printInst("brmi"); return true;              // 1111 00kk kkkk k010
      case 0xF003: printInst("brvs"); return true;              // 1111 00kk kkkk k011
      case 0xF004: printInst("brlt"); return true;              // 1111 00kk kkkk k100
      case 0xF005: printInst("brhs"); return true;              // 1111 00kk kkkk k101
      case 0xF006: printInst("brts"); return true;              // 1111 00kk kkkk k110
      case 0xF007: printInst("brie"); return true;              // 1111 00kk kkkk k111
      case 0xF400: printInst("brcc"); return true;              // 1111 01kk kkkk k000
      case 0xF401: printInst("brne"); return true;              // 1111 01kk kkkk k001
      case 0xF402: printInst("brpl"); return true;              // 1111 01kk kkkk k010
      case 0xF403: printInst("brvc"); return true;              // 1111 01kk kkkk k011
      case 0xF404: printInst("brge"); return true;              // 1111 01kk kkkk k100
      case 0xF405: printInst("brhc"); return true;              // 1111 01kk kkkk k101
      case 0xF406: printInst("brtc"); return true;              // 1111 01kk kkkk k110
      case 0xF407: printInst("brid"); return true;              // 1111 01kk kkkk k111
    }
    return false;
  }

  private boolean dAsmMul (int opcode) {
    switch (opcode & 0xFF88) {
      case 0x0300: printInst("mulsu");   return true;           // 0000 0011 0ddd 0rrr
      case 0x0308: printInst("fmul");    return true;           // 0000 0011 0ddd 1rrr
      case 0x0380: printInst("fmuls");   return true;           // 0000 0011 1ddd 0rrr
      case 0x0388: printInst("fmulsu");  return true;           // 0000 0011 1ddd 1rrr
    }
    return false;
  }

  String getDisAsm () {
    return new String(bout.toByteArray(), StandardCharsets.UTF_8);
  }

  /*
   *  Test Code for Disassembler
   */

  public static void main (String[] args) throws Exception {
    ByteArrayOutputStream buf = new ByteArrayOutputStream();
    int base = 0;
    InputStream fis;
    fis = AVRDisassembler.class.getResourceAsStream("optiboot_atmega328.hex");
    byte[] data = new byte[fis.available()];
    if (fis.read(data) == data.length) {
      fis.close();
      String hex = new String(data, StandardCharsets.UTF_8);
      boolean needAddr = true;
      StringTokenizer tok = new StringTokenizer(hex, "\n\r");
      while (tok.hasMoreTokens()) {
        String line = tok.nextToken();
        if (line.startsWith(":")) {
          if (needAddr) {
            String add = line.substring(3, 7);
            base = Integer.parseInt(add, 16);
            needAddr = false;
          }
          String type = line.substring(7, 9);
          line = line.substring(9, line.length() - 2);
          if (type.equals("00")) {
            int len = line.length();
            for (int ii = 0; ii < len; ii += 2) {
              String bHex = line.substring(ii, ii + 2);
              byte tmp = (byte) Integer.parseInt(bHex, 16);
              buf.write(tmp);
            }
          }
        }
      }
      byte[] code = buf.toByteArray();
      AVRDisassembler disAsm = new AVRDisassembler();
      disAsm.dAsm(code, 0, base, code.length / 2);
      System.out.println(disAsm.getDisAsm());
    }
  }
}
