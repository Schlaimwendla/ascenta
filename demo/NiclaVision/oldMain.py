import bluetooth
import time
import struct
import sensor
import image
import gc
import uos
import audio
import micropython
import ml
from pyb import LED

micropython.alloc_emergency_exception_buf(100)

led_red = LED(1)
led_green = LED(2)
led_blue = LED(3)

_NICLA_SERVICE_UUID = bluetooth.UUID("12345678-1234-5678-1234-567890ABCDEF")
_DATA_CHAR_UUID     = bluetooth.UUID("12345678-1234-5678-1234-567890ABCDE0")
_CMD_SERVICE_UUID   = bluetooth.UUID("80000000-0000-1000-8000-00805f9b34fb")
_CMD_CHAR_UUID      = bluetooth.UUID("80000001-0000-1000-8000-00805f9b34fb")

_UART_SERVICE_UUID = bluetooth.UUID("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
_UART_TX_UUID      = bluetooth.UUID("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")
_UART_RX_UUID      = bluetooth.UUID("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")

_FLAG_READ_NOTIFY = bluetooth.FLAG_READ | bluetooth.FLAG_NOTIFY
_FLAG_WRITE       = bluetooth.FLAG_WRITE

_NICLA_SERVICE_DEF = (_NICLA_SERVICE_UUID, ((_DATA_CHAR_UUID, _FLAG_READ_NOTIFY),))
_CMD_SERVICE_DEF   = (_CMD_SERVICE_UUID,   ((_CMD_CHAR_UUID, _FLAG_WRITE),))
_UART_SERVICE_DEF  = (_UART_SERVICE_UUID,  ((_UART_TX_UUID, _FLAG_READ_NOTIFY), (_UART_RX_UUID, _FLAG_WRITE),))

IMAGE_START_SEQ = b'\xAA\xBB\xCC\xDD'
IMAGE_END_SEQ   = b'\xDD\xCC\xBB\xAA'

REC_SECONDS = 4.0
SAMPLE_RATE = 16000
CHANNELS = 1
BIT_DEPTH = 16
BYTES_PER_SAMPLE = 2
TOTAL_AUDIO_BYTES = int(SAMPLE_RATE * CHANNELS * BYTES_PER_SAMPLE * REC_SECONDS)
AUDIO_CHUNK_SIZE = 4096
NUM_AUDIO_CHUNKS = (TOTAL_AUDIO_BYTES // AUDIO_CHUNK_SIZE) + 1

g_audio_chunks = []
g_audio_written = 0

class NiclaUnified:
    def __init__(self):
        self.ble = bluetooth.BLE()
        self.ble.active(True)

        self.h_img_data = None
        self.h_img_cmd  = None
        self.h_uart_tx  = None
        self.h_uart_rx  = None

        self.conn_handle = None
        self.payload_size = 20

        self.req_image = False
        self.req_audio = False
        self.recording = False

        self.model = None
        self.kws_threshold = 0.8

        self._init_hardware()
        self._setup_ble()
        self._load_kws()

        led_green.on()

    def _init_hardware(self):
        try:
            sensor.reset()
            sensor.set_pixformat(sensor.RGB565)
            sensor.set_framesize(sensor.QVGA)
            sensor.skip_frames(time=2000)
        except: pass

        try:
            audio.init(channels=CHANNELS, frequency=SAMPLE_RATE, gain_db=18)
        except: pass

    def _load_kws(self):
        try:
            self.model = ml.Model("trained")
        except: pass

    def _setup_ble(self):
        self.ble.irq(self._irq)
        handles = self.ble.gatts_register_services((_NICLA_SERVICE_DEF, _CMD_SERVICE_DEF, _UART_SERVICE_DEF))
        self.h_img_data = handles[0][0]
        self.h_img_cmd  = handles[1][0]
        self.h_uart_tx  = handles[2][0]
        self.h_uart_rx  = handles[2][1]
        name = b"Nicla"
        adv = b'\x02\x01\x06' + struct.pack('<BB', len(name)+1, 0x09) + name
        self.ble.gap_advertise(100000, adv_data=adv)

    def _irq(self, event, data):
        if event == 1:
            self.conn_handle = data[0]
            led_green.off()
        elif event == 2:
            self.conn_handle = None
            self.req_image = False
            self.req_audio = False
            self.payload_size = 20
            led_blue.off(); led_red.off(); led_green.on()
            self.ble.gap_advertise(100000)
        elif event == 3:
            conn, value_handle = data
            if value_handle == self.h_img_cmd:
                try:
                    cmd = self.ble.gatts_read(self.h_img_cmd).decode().strip().lower()
                    if cmd == "take_picture": self.req_image = True
                except: pass
            elif value_handle == self.h_uart_rx:
                try:
                    cmd = self.ble.gatts_read(self.h_uart_rx).decode().strip()
                    if cmd == "RECORD": self.req_audio = True
                except: pass
        elif event == 21:
            self.payload_size = data[1] - 3

    def listen_kws(self):
        if not self.model or self.recording or self.req_audio or self.req_image:
            return

        try:
            audio_data = audio.record(1.0)
            predictions = self.model.predict([audio_data])[0].flatten().tolist()

            if predictions[1] > self.kws_threshold:
                self.req_audio = True
                led_blue.on()
                time.sleep_ms(200)
                led_blue.off()
        except:
            pass

    def process_image(self):
        self.req_image = False
        led_blue.on()
        global g_audio_chunks
        g_audio_chunks = []
        gc.collect()
        path = "temp.jpg"
        try:
            img = sensor.snapshot()
            img.save(path, quality=75)
            del img
            gc.collect()
            with open(path, 'rb') as f:
                self._send_raw(self.h_img_data, IMAGE_START_SEQ)
                buf = bytearray(self.payload_size)
                while True:
                    n = f.readinto(buf)
                    if not n or not self.conn_handle: break
                    self._send_raw(self.h_img_data, memoryview(buf)[:n])
                    time.sleep_ms(15)
                self._send_raw(self.h_img_data, IMAGE_END_SEQ)
            uos.remove(path)
        except: pass
        led_blue.off()
        gc.collect()

    def process_audio(self):
        self.req_audio = False
        led_red.on()
        global g_audio_chunks, g_audio_written
        g_audio_chunks = []
        gc.collect()
        try:
            for i in range(NUM_AUDIO_CHUNKS):
                g_audio_chunks.append(bytearray(AUDIO_CHUNK_SIZE))
        except:
            led_red.off(); return
        self.rec_idx = 0
        self.rec_offset = 0
        g_audio_written = 0
        self.recording = True
        gc.disable()
        try:
            audio.start_streaming(self._audio_callback)
            while self.recording:
                time.sleep_ms(50)
            audio.stop_streaming()
        except: pass
        gc.enable()
        led_red.off()
        if g_audio_written % 2 != 0:
            g_audio_written -= 1
        led_blue.on()
        try:
            total_len = 44 + g_audio_written
            self._send_raw(self.h_uart_tx, f"START:{total_len}\n".encode())
            time.sleep_ms(100)
            header = struct.pack('<4sI4s4sIHHIIHH4sI', b'RIFF', 36+g_audio_written, b'WAVE', b'fmt ', 16, 1, CHANNELS, SAMPLE_RATE, SAMPLE_RATE*CHANNELS*BYTES_PER_SAMPLE, CHANNELS*BYTES_PER_SAMPLE, BIT_DEPTH, b'data', g_audio_written)
            self._send_raw(self.h_uart_tx, header)
            for i in range(self.rec_idx + 1):
                if not self.conn_handle: break
                chunk = g_audio_chunks[i]
                length = AUDIO_CHUNK_SIZE
                if i == self.rec_idx: length = self.rec_offset
                if length > 0:
                    mv = memoryview(chunk)[:length]
                    sent = 0
                    while sent < length:
                        size = min(self.payload_size, length - sent)
                        self._send_raw(self.h_uart_tx, mv[sent:sent+size])
                        sent += size
                        time.sleep_ms(20)
                g_audio_chunks[i] = None
                if i%5==0: gc.collect()
            self._send_raw(self.h_uart_tx, b"END\n")
        except: pass
        led_blue.off()
        gc.collect()

    def _audio_callback(self, buf):
        global g_audio_written
        if not self.recording: return
        mv = memoryview(buf)
        l = len(mv)
        off = 0
        while l > 0:
            if self.rec_idx >= len(g_audio_chunks): self.recording = False; break
            chunk = g_audio_chunks[self.rec_idx]
            space = AUDIO_CHUNK_SIZE - self.rec_offset
            amt = min(l, space)
            chunk[self.rec_offset : self.rec_offset+amt] = mv[off : off+amt]
            self.rec_offset += amt
            off += amt
            l -= amt
            g_audio_written += amt
            if self.rec_offset >= AUDIO_CHUNK_SIZE:
                self.rec_idx += 1
                self.rec_offset = 0
        if g_audio_written >= TOTAL_AUDIO_BYTES: self.recording = False

    def _send_raw(self, handle, data):
        try:
            self.ble.gatts_notify(self.conn_handle, handle, data)
        except: pass

if __name__ == "__main__":
    nicla = NiclaUnified()
    while True:
        if nicla.req_image:
            nicla.process_image()
        elif nicla.req_audio:
            nicla.process_audio()
        else:
            nicla.listen_kws()
        time.sleep_ms(10)
