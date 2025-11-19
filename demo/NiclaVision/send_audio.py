import audio
import time
import struct
import uos
import bluetooth
import gc
import micropython
from pyb import LED

# Emergency buffer for interrupts
micropython.alloc_emergency_exception_buf(100)

# === CONFIGURATION ===
RECORD_SECONDS = 4.0
SAMPLE_RATE = 16000
CHANNELS = 1
BIT_DEPTH = 16
BYTES_PER_SAMPLE = BIT_DEPTH // 8
TOTAL_BYTES = int(SAMPLE_RATE * CHANNELS * BYTES_PER_SAMPLE * RECORD_SECONDS)

CHUNK_SIZE = 4096
NUM_CHUNKS = (TOTAL_BYTES // CHUNK_SIZE) + 1

# RAM Buffers
g_chunks = []
g_curr_chunk_idx = 0
g_curr_chunk_offset = 0
g_total_written = 0
g_is_recording = False

# LEDs: 1=Red, 2=Green, 3=Blue
led_red = LED(1)
led_green = LED(2)
led_blue = LED(3)

BLE_DEVICE_NAME = b"NiclaAudio"
_UART_UUID = bluetooth.UUID("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
_RX_UUID = bluetooth.UUID("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
_TX_UUID = bluetooth.UUID("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")
_DATA_CHAR = (_TX_UUID, bluetooth.FLAG_NOTIFY | bluetooth.FLAG_READ)
_COMMAND_CHAR = (_RX_UUID, bluetooth.FLAG_WRITE)
_AUDIO_SERVICE = (_UART_UUID, (_DATA_CHAR, _COMMAND_CHAR))

def create_wav_header(data_size):
    chunk_size = 36 + data_size
    return struct.pack('<4sI4s4sIHHIIHH4sI', b'RIFF', chunk_size, b'WAVE', b'fmt ', 16, 1, CHANNELS, SAMPLE_RATE, SAMPLE_RATE*CHANNELS*BYTES_PER_SAMPLE, CHANNELS*BYTES_PER_SAMPLE, BIT_DEPTH, b'data', data_size)

def audio_callback(buf):
    global g_curr_chunk_idx, g_curr_chunk_offset, g_total_written, g_is_recording
    if not g_is_recording: return

    mv_buf = memoryview(buf)
    bytes_to_write = len(mv_buf)
    buf_offset = 0

    while bytes_to_write > 0:
        if g_curr_chunk_idx >= len(g_chunks):
            g_is_recording = False; break

        current_chunk = g_chunks[g_curr_chunk_idx]
        space = CHUNK_SIZE - g_curr_chunk_offset
        copy_len = min(bytes_to_write, space)

        current_chunk[g_curr_chunk_offset : g_curr_chunk_offset + copy_len] = mv_buf[buf_offset : buf_offset + copy_len]

        g_curr_chunk_offset += copy_len
        buf_offset += copy_len
        bytes_to_write -= copy_len
        g_total_written += copy_len

        if g_curr_chunk_offset >= CHUNK_SIZE:
            g_curr_chunk_idx += 1
            g_curr_chunk_offset = 0

    if g_total_written >= TOTAL_BYTES: g_is_recording = False

def record_to_ram():
    global g_chunks, g_curr_chunk_idx, g_curr_chunk_offset, g_total_written, g_is_recording

    led_red.on()
    led_green.off()
    led_blue.off()

    gc.collect()
    g_chunks = []
    gc.collect()

    print(f"Allocating RAM...")
    try:
        for i in range(NUM_CHUNKS):
            g_chunks.append(bytearray(CHUNK_SIZE))
    except MemoryError:
        print("MemErr")
        led_red.off(); return False

    try: audio.init(channels=CHANNELS, frequency=SAMPLE_RATE, gain_db=18, highpass=0.9883)
    except: print("FreqErr"); led_red.off(); return False

    g_curr_chunk_idx = 0; g_curr_chunk_offset = 0; g_total_written = 0; g_is_recording = True

    # CRITICAL: Disable GC during recording to prevent audio skips
    gc.disable()

    audio.start_streaming(audio_callback)
    print("Recording...")

    while g_is_recording:
        time.sleep_ms(50)

    audio.stop_streaming()
    gc.enable()

    led_red.off()
    print(f"Done. {g_total_written} bytes.")
    return True

class BLEApp:
    def __init__(self):
        self.ble = bluetooth.BLE()
        self.ble.active(True)
        self.ble.irq(self._irq)
        ((self.h_tx, self.h_rx),) = self.ble.gatts_register_services((_AUDIO_SERVICE,))
        self.conn = None
        self.start_transfer = False
        self.start_recording = False
        self.payload_size = 20
        self.ble.gap_advertise(100000, adv_data=bytearray(b'\x02\x01\x06') + bytearray([len(BLE_DEVICE_NAME)+1, 9]) + BLE_DEVICE_NAME)
        led_green.on()
        print("Advertising...")

    def _irq(self, event, data):
        if event == 1:
            self.conn = data[0]
            led_green.off()
            print("Connected.")
        elif event == 2:
            self.conn = None
            self.start_transfer = False
            led_blue.off()
            led_green.on()
            print("Disconnected.")
            self.payload_size = 20
            self.ble.gap_advertise(100000, adv_data=bytearray(b'\x02\x01\x06') + bytearray([len(BLE_DEVICE_NAME)+1, 9]) + BLE_DEVICE_NAME)
        elif event == 3:
            if data[1] == self.h_rx:
                try:
                    cmd = self.ble.gatts_read(self.h_rx).decode().strip()
                    if cmd == "START" and self.conn:
                        self.start_transfer = True
                    elif cmd == "RECORD":
                        self.start_recording = True
                except: pass
        elif event == 21:
            mtu = data[1]
            self.payload_size = mtu - 3
            print(f"MTU: {mtu}")

    def send_ram_data(self):
        print("Streaming...")
        led_blue.on()
        self.start_transfer = False
        gc.collect()

        try:
            total_size = 44 + g_total_written
            self.send(f"START:{total_size}\n".encode())
            time.sleep_ms(100) # Wait for phone to prep buffer

            header = create_wav_header(g_total_written)
            self.send(header)

            for i in range(g_curr_chunk_idx + 1):
                if not self.conn: break

                chunk = g_chunks[i]
                data_len = CHUNK_SIZE
                if i == g_curr_chunk_idx:
                    data_len = g_curr_chunk_offset

                if data_len > 0:
                    self.send(memoryview(chunk)[:data_len])

                g_chunks[i] = None
                if i % 5 == 0: gc.collect()

            if self.conn:
                self.ble.gatts_notify(self.conn, self.h_tx, b"END\n")
            print("Sent.")

        except Exception as e:
            print(f"SendErr: {e}")
        finally:
            led_blue.off()
            gc.collect()

    def send(self, data):
        mv = memoryview(data)
        bytes_sent = 0
        total_len = len(data)

        while bytes_sent < total_len:
            if not self.conn: break
            chunk_len = min(self.payload_size, total_len - bytes_sent)
            try:
                self.ble.gatts_notify(self.conn, self.h_tx, mv[bytes_sent : bytes_sent + chunk_len])
                bytes_sent += chunk_len
            except OSError:
                pass # Buffer full, just retry logic implies wait

            # Increased delay to 35ms to prevent packet loss
            time.sleep_ms(15)

if __name__ == "__main__":
    if record_to_ram():
        app = BLEApp()
        while True:
            if app.start_transfer: app.send_ram_data()
            if app.start_recording:
                app.start_recording = False
                if app.conn: app.ble.gap_disconnect(app.conn)
                time.sleep_ms(1000)
                record_to_ram()
            time.sleep_ms(100)
