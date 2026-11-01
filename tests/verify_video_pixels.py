#!/usr/bin/env python3
"""Decode real libretro RGB565 callbacks and save PNG/JSON evidence.
Usage: verify_video_pixels.py LIBGENESIS ROM [OUTDIR] [FRAMES]
No copyrighted ROM is stored by this script; supply a local path.
"""
import ctypes as C, sys, hashlib, json
from pathlib import Path
from PIL import Image, ImageDraw

lib = C.CDLL(sys.argv[1]); rom = sys.argv[2].encode()
outdir = Path(sys.argv[3] if len(sys.argv) > 3 else "video-proof"); outdir.mkdir(parents=True, exist_ok=True)
maxframes = int(sys.argv[4]) if len(sys.argv) > 4 else 900
class Info(C.Structure): _fields_=[('library_name',C.c_char_p),('library_version',C.c_char_p),('valid_extensions',C.c_char_p),('need_fullpath',C.c_bool),('block_extract',C.c_bool)]
class Game(C.Structure): _fields_=[('path',C.c_char_p),('data',C.c_void_p),('size',C.c_size_t),('meta',C.c_char_p)]
class Geometry(C.Structure): _fields_=[('base_width',C.c_uint),('base_height',C.c_uint),('max_width',C.c_uint),('max_height',C.c_uint),('aspect_ratio',C.c_float)]
class Timing(C.Structure): _fields_=[('fps',C.c_double),('sample_rate',C.c_double)]
class Av(C.Structure): _fields_=[('geometry',Geometry),('timing',Timing)]
ENV=C.CFUNCTYPE(C.c_bool,C.c_uint,C.c_void_p); VIDEO=C.CFUNCTYPE(None,C.c_void_p,C.c_uint,C.c_uint,C.c_size_t); AUDIO=C.CFUNCTYPE(C.c_size_t,C.POINTER(C.c_int16),C.c_size_t); POLL=C.CFUNCTYPE(None); INPUT=C.CFUNCTYPE(C.c_int16,C.c_uint,C.c_uint,C.c_uint,C.c_uint)
frames=[]; count=0; pixel_format=None
want={1,2,30,60,120,180,300,600,900,1200,1800}
def env(cmd,data):
    global pixel_format
    if cmd==10:
        pixel_format=C.cast(data,C.POINTER(C.c_int)).contents.value
        C.cast(data,C.POINTER(C.c_int)).contents.value=2; return True
    if cmd==16: C.cast(data,C.POINTER(C.c_bool)).contents.value=True; return True
    return cmd in (14,21,22,35,36,65587)
def video(data,w,h,pitch):
    global count
    count+=1
    if data and count in want and count<=maxframes:
        raw=C.string_at(data,pitch*h)
        vals=[int.from_bytes(raw[y*pitch+x*2:y*pitch+x*2+2],'little') for y in range(h) for x in range(w)]
        rgb=bytes(c for p in vals for c in (((p>>11)&31)*255//31,((p>>5)&63)*255//63,(p&31)*255//31))
        im=Image.frombytes('RGB',(w,h),rgb); im.save(outdir/f'frame-{count:04d}.png')
        frames.append(dict(frame=count,width=w,height=h,pitch=pitch,colors=len(set(vals)),nonzero=sum(p!=0 for p in vals),sha256=hashlib.sha256(raw).hexdigest()))
def audio(data,n): return n
callbacks=[ENV(env),VIDEO(video),AUDIO(audio),POLL(lambda:None),INPUT(lambda *a:0)]
for name,cb in zip(['retro_set_environment','retro_set_video_refresh','retro_set_audio_sample_batch','retro_set_input_poll','retro_set_input_state'],callbacks): getattr(lib,name)(cb)
lib.retro_get_system_info.argtypes=[C.POINTER(Info)]; info=Info(); lib.retro_get_system_info(C.byref(info))
lib.retro_init(); lib.retro_load_game.argtypes=[C.POINTER(Game)]
assert lib.retro_load_game(C.byref(Game(rom,None,0,None))), "ROM load failed"
lib.retro_get_system_av_info.argtypes=[C.POINTER(Av)]; av=Av(); lib.retro_get_system_av_info(C.byref(av))
lib.retro_set_controller_port_device(0,513)
for _ in range(maxframes): lib.retro_run()
lib.retro_unload_game(); lib.retro_deinit()
if frames:
    sheet=Image.new('RGB',(3*340,((len(frames)+2)//3)*270),(35,35,35)); draw=ImageDraw.Draw(sheet)
    for i,f in enumerate(frames):
        im=Image.open(outdir/f"frame-{f['frame']:04d}.png"); sheet.paste(im,(i%3*340,i//3*270+24))
        draw.text((i%3*340+5,i//3*270+5),f"frame {f['frame']} {f['width']}x{f['height']} lit={f['nonzero']}",fill='white')
    sheet.save(outdir/'contact-sheet.png')
result=dict(core=info.library_name.decode(),version=info.library_version.decode(),pixel_format=pixel_format,callbacks=count,frames=frames)
(outdir/'result.json').write_text(json.dumps(result,indent=2)); print(json.dumps(result,indent=2))
assert count>=min(maxframes,60), "Too few video callbacks"
assert any(f['nonzero']>0 for f in frames), "All sampled frames are black"
