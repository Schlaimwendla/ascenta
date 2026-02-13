import audio
import gc
import struct
import time
import uos
import config
import tf
import micro_speech
from pyb import LED

led_red = LED(1)

g_chunks = []
g_curr_chunk_idx = 0
g_curr_chunk_offset = 0
g_total_written = 0
g_is_recording = False

def init_hardware():
    try:
        audio.init(channels=config.CHANNELS, frequency=config.SAMPLE_RATE, gain_db=18, highpass=0.9883)
        print("Audio HW Init OK")
        return True
    except Exception as e:
        print(f"Audio Init Err: {e}")
        return False

def allocate_buffer():
    global g_chunks
    if len(g_chunks) > 0:
        return True

    gc.collect()
    print("Allocating Audio Buffer...")
    try:
        g_chunks = []
        for i in range(config.NUM_AUDIO_CHUNKS):
            g_chunks.append(bytearray(config.AUDIO_CHUNK_SIZE))
        print(f"Allocated {len(g_chunks)} chunks.")
        return True
    except MemoryError:
        print("Audio OOM: RAM Full")
        g_chunks = []
        return False

def create_header(data_size):
    return struct.pack('<4sI4s4sIHHIIHH4sI', b'RIFF', 36+data_size, b'WAVE', b'fmt ', 16, 1,
                       config.CHANNELS, config.SAMPLE_RATE,
                       config.SAMPLE_RATE*config.CHANNELS*config.BYTES_PER_SAMPLE,
                       config.CHANNELS*config.BYTES_PER_SAMPLE, 16, b'data', data_size)

def _callback(buf):
    global g_curr_chunk_idx, g_curr_chunk_offset, g_total_written, g_is_recording
    if not g_is_recording: return

    mv_buf = memoryview(buf)
    bytes_to_write = len(mv_buf)
    buf_offset = 0

    while bytes_to_write > 0:
        if g_curr_chunk_idx >= len(g_chunks):
            g_is_recording = False; break

        current_chunk = g_chunks[g_curr_chunk_idx]
        space = config.AUDIO_CHUNK_SIZE - g_curr_chunk_offset
        copy_len = min(bytes_to_write, space)

        current_chunk[g_curr_chunk_offset : g_curr_chunk_offset + copy_len] = mv_buf[buf_offset : buf_offset + copy_len]

        g_curr_chunk_offset += copy_len
        buf_offset += copy_len
        bytes_to_write -= copy_len
        g_total_written += copy_len

        if g_curr_chunk_offset >= config.AUDIO_CHUNK_SIZE:
            g_curr_chunk_idx += 1
            g_curr_chunk_offset = 0

    if g_total_written >= config.TOTAL_AUDIO_BYTES: g_is_recording = False

def record():
    global g_curr_chunk_idx, g_curr_chunk_offset, g_total_written, g_is_recording

    if not g_chunks:
        if not allocate_buffer():
            led_red.off()
            return False

    led_red.on()
    gc.collect()

    g_curr_chunk_idx = 0; g_curr_chunk_offset = 0; g_total_written = 0; g_is_recording = True

    try:
        audio.start_streaming(_callback)
    except Exception as e:
        print(f"Stream Err: {e}")
        led_red.off(); return False

    start = time.ticks_ms()
    while g_is_recording:
        time.sleep_ms(50)
        if time.ticks_diff(time.ticks_ms(), start) > (config.REC_SECONDS * 1000) + 500:
            break

    audio.stop_streaming()
    led_red.off()
    return True

def save_last_recording():
    print("Saving to audio.wav...")
    try:
        with open("audio.wav", "wb") as f:
            f.write(create_header(g_total_written))
            for i in range(g_curr_chunk_idx + 1):
                chunk = g_chunks[i]
                length = config.AUDIO_CHUNK_SIZE
                if i == g_curr_chunk_idx: length = g_curr_chunk_offset

                if length > 0:
                    f.write(memoryview(chunk)[:length])
        print("Saved.")
        return True
    except Exception as e:
        print(f"Save Failed: {e}")
        return False

def listen_for_keyword():
    # Placeholder for MicroSpeech logic
    # Requires 'keywords.tflite' trained on Edge Impulse
    try:
        if "keywords.tflite" not in uos.listdir():
            return False

        # This is a blocking call example. In reality, you'd likely
        # need to interleave this with BLE polling or run it in a loop
        # that returns True if detected.

        # Simpler approach: Just return False if no model, or implement
        # tf.load logic here if you have the model ready.
        return False

    except:
        return False
