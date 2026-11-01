#!/usr/bin/env python3
"""Generate an original, public-domain Genesis video/input test ROM.
No copyrighted game data. The program displays a colored backdrop and copies
controller bits into CRAM color zero, so device tests can verify real pixels/input.
"""
import struct,sys
from pathlib import Path
rom=bytearray(128*1024)
struct.pack_into('>II',rom,0,0x00FFFE00,0x00000200)
rom[0x100:0x110]=b'SEGA MEGA DRIVE '
rom[0x120:0x150]=b'UMK3 VIDEO TEST '.ljust(48,b' ')
rom[0x150:0x160]=b'ARENA TEST 0001 '.ljust(16,b' ')
rom[0x180:0x182]=b'GM'
rom[0x190:0x193]=b'JUE'
rom[0x1F0:0x1F5]=b'WORLD'
code=bytearray()
def w(v):code.extend(struct.pack('>H',v&0xffff))
def long(v):code.extend(struct.pack('>I',v&0xffffffff))
def vdp(reg,val):w(0x33FC);w(0x8000|(reg<<8)|val);long(0xC00004)
def cram(color):
 w(0x23FC);long(0xC0000000);long(0xC00004);w(0x33FC);w(color);long(0xC00000)
w(0x46FC);w(0x2700)
for reg,val in {0:0x04,1:0x74,2:0x30,3:0,4:0x07,5:0,7:0,10:0xff,11:0,12:1,15:2,16:1}.items():vdp(reg,val)
cram(0x00E0) # green backdrop before input
for addr in (0xA10009,0xA10003,0xA1000B,0xA10005):w(0x13FC);w(0x40);long(addr)
loop=len(code)
w(0x13FC);w(0x00);long(0xA10003) # TH low: Start/A
w(0x1039);long(0xA10003) # move.b pad,d0
w(0x4600) # not.b d0
w(0x0200);w(0x3f)
w(0x3200) # d1=d0
w(0xD241) # d1+=d1
w(0x23FC);long(0xC0000000);long(0xC00004)
w(0x33C1);long(0xC00000)
w(0x13FC);w(0x40);long(0xA10003) # TH high: C/B/directions
w(0x6000|((loop-len(code)-2)&0xff))
rom[0x200:0x200+len(code)]=code
struct.pack_into('>I',rom,0x180+10,0) # keep IO support string region harmless
struct.pack_into('>I',rom,0x1A4,0);struct.pack_into('>I',rom,0x1A8,len(rom)-1)
struct.pack_into('>H',rom,0x18E,sum(struct.unpack('>%dH'%(len(rom[0x200:])//2),rom[0x200:]))&0xffff)
out=Path(sys.argv[1] if len(sys.argv)>1 else 'video-test.gen');out.write_bytes(rom);print(out,len(rom),'bytes')
