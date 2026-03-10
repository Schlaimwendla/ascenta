import bluetooth
import network
import socket
import struct
import time
import sensor
import image
import gc
import uos
import audio
import micropython
import ml
import os
from machine import I2C, Pin, SPI
import vl53l1x
from lsm6dsox import LSM6DSOX
from pyb import LED

# Reserve memory for exceptions in interrupts
micropython.alloc_emergency_exception_buf(100)

led_red = LED(1)
led_green = LED(2)
led_blue = LED(3)

PROV_SERVICE_UUID = bluetooth.UUID("12345678-1234-5678-1234-567890ABCDEF")
SSID_CHAR_UUID    = bluetooth.UUID("12345678-1234-5678-1234-567890ABCDE1")
PASS_CHAR_UUID    = bluetooth.UUID("12345678-1234-5678-1234-567890ABCDE2")

UDP_IP = "255.255.255.255"
TCP_PORT = 5005
UDP_DISC_PORT = 5006

# Audio recording settings
REC_SECONDS = 2.5
SAMPLE_RATE = 16000
CHANNELS = 1
BYTES_PER_SAMPLE = 2
TOTAL_AUDIO_BYTES = int(SAMPLE_RATE * CHANNELS * BYTES_PER_SAMPLE * REC_SECONDS)
AUDIO_CHUNK_SIZE = 1024
NUM_AUDIO_CHUNKS = (TOTAL_AUDIO_BYTES // AUDIO_CHUNK_SIZE) + 1

FUEL_GAUGE_ADDR = 0x36

g_audio_chunks = []
g_audio_written = 0
g_ssid = ""
g_pass = ""
g_wifi_connected = False

class NiclaSystem:
    def __init__(self):
        self.server_sock = None
        self.tcp_conn = None
        self.last_broadcast = 0
        self.last_ml_print = time.ticks_ms()
        self.last_bat_check = 0
        self.wlan = None
        self.ble = bluetooth.BLE()
        self.ble.active(True)
        self.conn_handle = None
        self.req_image = False
        self.req_audio = False
        self.recording = False
        self.trigger_wifi_connect = False
        self.net = None
        self.labels = None
        self.tof = None
        self.lsm = None
        self.fuel_gauge_i2c = None
        self.sent_initial_bat = False
        self.low_bat_warned = False
        self.connection_time = 0

        # New IMU variables for Delta-Tap Detection
        self.last_tap_time = 0
        self.last_accel = (0, 0, 0)

        self._init_hardware()

        global g_audio_chunks
        try:
            gc.collect()
            g_audio_chunks = [bytearray(AUDIO_CHUNK_SIZE) for _ in range(NUM_AUDIO_CHUNKS)]
        except Exception as e:
            print("DEBUG [System]: CRITICAL - Audio chunks alloc failed:", e)

        saved = self._load_config()
        if saved:
            global g_ssid, g_pass
            g_ssid, g_pass = saved
            print("DEBUG [System]: Loaded saved WiFi config:", g_ssid)
            self.trigger_wifi_connect = True
        else:
            print("DEBUG [System]: No WiFi config found, starting BLE provisioning")
            self._setup_ble_provisioning()

    def _save_config(self, ssid, password):
        try:
            with open("wifi.txt", "w") as f:
                f.write(ssid + "\n")
                f.write(password)
        except Exception as e:
            print("DEBUG [System]: Save config failed:", e)

    def _load_config(self):
        try:
            with open("wifi.txt", "r") as f:
                lines = f.readlines()
                if len(lines) >= 2:
                    return lines[0].strip(), lines[1].strip()
        except: pass
        return None

    def _init_hardware(self):
        print("DEBUG [System]: Starting hardware init")

        try:
            sensor.reset()
            sensor.set_pixformat(sensor.RGB565)
            sensor.set_framesize(sensor.QVGA)
            sensor.set_windowing((240, 240))
            sensor.skip_frames(time=2000)
            print("DEBUG [System]: Sensor initialized (240x240 Window)")
        except Exception as e:
            print("DEBUG [System]: Sensor init failed:", e)

        try:
            i2c_bus = I2C(2)
            devices = i2c_bus.scan()
            print("DEBUG [I2C]: Scanned devices on Bus 2:", [hex(d) for d in devices])

            # Init ToF
            try:
                self.tof = vl53l1x.VL53L1X(i2c_bus)
                print("DEBUG [System]: ToF sensor initialized")
            except:
                print("DEBUG [System]: ToF init failed")
                self.tof = None

            # Init Fuel Gauge
            if FUEL_GAUGE_ADDR in devices:
                self.fuel_gauge_i2c = i2c_bus
                print("DEBUG [Fuel Gauge]: MAX17262 FOUND at 0x36!")
            else:
                print("DEBUG [Fuel Gauge]: MAX17262 NOT FOUND on Bus 2!")
        except Exception as e:
            print("DEBUG [System]: I2C Bus 2 init failed:", e)

        try:
            self.lsm = LSM6DSOX(SPI(5), cs=Pin("PF6", Pin.OUT_PP, Pin.PULL_UP))
            print("DEBUG [System]: IMU initialized")
        except: self.lsm = None

        try:
            audio.init(channels=CHANNELS, frequency=SAMPLE_RATE, gain_db=18)
            print("DEBUG [System]: Audio initialized")
        except: pass

        try:
            gc.collect()
            print("DEBUG [FOMO]: Loading trained.tflite...")
            self.net = ml.Model("trained.tflite", load_to_fb=True)
            print("DEBUG [FOMO]: Model successfully loaded!")

            self.labels = [line.rstrip('\n') for line in open("labels.txt")]
            print(f"DEBUG [FOMO]: Labels loaded: {self.labels}")
        except Exception as e:
            print("DEBUG [FOMO]: CRITICAL - Model loading failed! Exception:", e)

    def get_soc(self):
        if not self.fuel_gauge_i2c:
            return None
        try:
            data = self.fuel_gauge_i2c.readfrom_mem(FUEL_GAUGE_ADDR, 0x06, 2)
            soc_raw = int.from_bytes(data, 'little')
            soc = soc_raw / 256.0
            return soc
        except:
            return None

    def _setup_ble_provisioning(self):
        self.ble.irq(self._ble_irq)
        prov_svc = (PROV_SERVICE_UUID, ((SSID_CHAR_UUID, bluetooth.FLAG_WRITE), (PASS_CHAR_UUID, bluetooth.FLAG_WRITE)))
        handles = self.ble.gatts_register_services((prov_svc,))
        self.h_ssid, self.h_pass = handles[0][0], handles[0][1]
        self._advertise()

    def _advertise(self):
        adv = b'\x02\x01\x06' + struct.pack('<BB', 6, 0x09) + b"Nicla"
        self.ble.gap_advertise(100000, adv_data=adv)
        led_blue.on()

    def _ble_irq(self, event, data):
        global g_ssid, g_pass
        if event == 1: led_blue.off()
        elif event == 3:
            conn, v_h = data
            if v_h == self.h_ssid: g_ssid = self.ble.gatts_read(self.h_ssid).decode().strip()
            elif v_h == self.h_pass:
                g_pass = self.ble.gatts_read(self.h_pass).decode().strip()
                if g_ssid and g_pass:
                    self._save_config(g_ssid, g_pass)
                    self.trigger_wifi_connect = True

    def connect_wifi(self):
        global g_ssid, g_pass, g_wifi_connected
        print(f"DEBUG [System]: Connecting to WiFi: {g_ssid}")
        self.ble.active(False)
        led_blue.off()
        self.wlan = network.WLAN(network.STA_IF)
        self.wlan.active(True)
        self.wlan.connect(g_ssid, g_pass)

        for _ in range(15):
            if self.wlan.isconnected(): break
            led_red.on(); time.sleep_ms(100); led_red.off(); time.sleep_ms(900)

        if self.wlan.isconnected():
            g_wifi_connected = True
            led_green.on()
            print("DEBUG [System]: WiFi connected! IP:", self.wlan.ifconfig()[0])
            self._setup_tcp_server()
        else:
            print("DEBUG [System]: WiFi connection failed")
            try: uos.remove("wifi.txt")
            except: pass
            self.ble.active(True)
            self._setup_ble_provisioning()

    def _setup_tcp_server(self):
        try:
            if self.server_sock: self.server_sock.close()
        except: pass
        self.server_sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.server_sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.server_sock.bind(('', TCP_PORT))
        self.server_sock.listen(1)
        self.server_sock.setblocking(False)
        self.tcp_conn = None
        self.last_broadcast = 0
        print(f"DEBUG [System]: TCP-Server listening on port {TCP_PORT}")

    def manage_connection(self):
        if not self.tcp_conn:
            try:
                conn, addr = self.server_sock.accept()
                conn.setblocking(False)
                self.tcp_conn = conn
                self.req_image = False
                self.req_audio = False
                self.sent_initial_bat = False
                self.connection_time = time.ticks_ms()
                print("DEBUG [System]: App connected from", addr)
            except OSError:
                if time.ticks_diff(time.ticks_ms(), self.last_broadcast) > 2000:
                    try:
                        udp = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
                        udp.sendto(b"NICLA_READY", (UDP_IP, UDP_DISC_PORT))
                        udp.close()
                    except: pass
                    self.last_broadcast = time.ticks_ms()
            return False

        try:
            data = self.tcp_conn.recv(128)
            if data:
                cmds = data.decode('utf-8').strip().split('\n')
                for cmd in cmds:
                    c = cmd.strip()
                    if c == "take_picture": self.req_image = True
                    elif c == "RECORD": self.req_audio = True
                    elif c == "ping": self.send_tcp_packet(b"pong\n")
            else:
                self.tcp_conn.close()
                self.tcp_conn = None
        except OSError as e:
            if e.args[0] != 11:
                self.tcp_conn.close()
                self.tcp_conn = None
        return True

    def send_tcp_packet(self, data):
        if not self.tcp_conn: return
        self.tcp_conn.setblocking(True)
        try: self.tcp_conn.sendall(data)
        except:
            self.tcp_conn.close()
            self.tcp_conn = None
        finally:
            if self.tcp_conn: self.tcp_conn.setblocking(False)

    def check_imu(self):
        if not self.lsm or self.req_audio or self.req_image: return
        try:
            # Measure Delta-G (Jerk) instead of Absolute Force to ignore spinning
            x, y, z = self.lsm.accel()

            dx = abs(x - self.last_accel[0])
            dy = abs(y - self.last_accel[1])
            dz = abs(z - self.last_accel[2])

            # Save current for next frame
            self.last_accel = (x, y, z)

            delta_g = dx + dy + dz

            # A delta > 1.2g between 10ms frames is a hard, sudden physical tap.
            if delta_g > 1.75:
                now = time.ticks_ms()
                diff = time.ticks_diff(now, self.last_tap_time)

                if 100 < diff < 600:
                    print(f"DEBUG [IMU]: DOUBLE TAP! (Delta {delta_g:.2f}g)")
                    self.send_tcp_packet(b'{"action": "tap"}\n')
                    self.req_audio = True
                    self.last_tap_time = 0
                    time.sleep_ms(300)
                elif diff > 600:
                    print(f"DEBUG [IMU]: First tap detected... (Delta {delta_g:.2f}g)")
                    self.last_tap_time = now
                    time.sleep_ms(100)
        except: pass

    def run_active_detection(self):
        if not self.net: return
        if self.req_image or self.req_audio: return

        if self.tcp_conn and not self.sent_initial_bat:
            if time.ticks_diff(time.ticks_ms(), self.connection_time) > 2000:
                soc = self.get_soc()
                if soc is not None:
                    msg = '{"type": "battery", "soc": "%.1f"}\n' % soc
                    self.send_tcp_packet(msg.encode())
                self.sent_initial_bat = True

        now = time.ticks_ms()
        if self.tcp_conn and time.ticks_diff(now, self.last_bat_check) > 60000:
            self.last_bat_check = now
            soc = self.get_soc()
            if soc is not None:
                if soc <= 10.5 and not self.low_bat_warned:
                    msg = '{"type": "battery_warning", "soc": "%.1f"}\n' % soc
                    self.send_tcp_packet(msg.encode())
                    self.low_bat_warned = True
                elif soc > 15.0:
                    self.low_bat_warned = False

        debug_print = False
        if time.ticks_diff(now, self.last_ml_print) > 3000:
            self.last_ml_print = now
            debug_print = True

        try:
            img = sensor.snapshot()
            distance_mm = 0
            if self.tof:
                try: distance_mm = self.tof.read()
                except: pass

            try:
                raw_output = self.net.predict([img])
                heatmap = raw_output[0]
                shape = heatmap.shape

                best_label, best_conf, best_x, best_y = None, 0.0, 0, 0

                if len(shape) == 4:
                    grid_y, grid_x, classes = shape[1], shape[2], shape[3]
                    cell_w, cell_h = img.width() / grid_x, img.height() / grid_y

                    for y in range(grid_y):
                        for x in range(grid_x):
                            for c in range(1, classes):
                                score = heatmap[0][y][x][c]
                                if score > 0.45 and score > best_conf:
                                    best_conf = score
                                    best_x = int(x * cell_w + (cell_w / 2))
                                    best_y = int(y * cell_h + (cell_h / 2))
                                    if self.labels and c < len(self.labels): best_label = self.labels[c]
                                    else: best_label = f"Class_{c}"

                if best_label:
                    json_str = '{"label": "%s", "conf": "%.2f", "dist": "%d", "x": "%d", "y": "%d"}\n' % (best_label, best_conf, distance_mm, best_x, best_y)
                    self.send_tcp_packet(json_str.encode())

            except ValueError as ve:
                pass
            except Exception as e:
                pass
            finally:
                gc.collect()

        except Exception as e:
            pass

    def process_image(self):
        self.req_image = False
        led_blue.on()
        path = "temp.jpg"
        try:
            img = sensor.snapshot()
            img.save(path, quality=40)
            del img
            gc.collect()
            size = os.stat(path)[6]
            self.send_tcp_packet(f"IMG_START:{size}\n".encode())
            with open(path, 'rb') as f:
                while True:
                    chunk = f.read(1024)
                    if not chunk: break
                    self.send_tcp_packet(chunk)
            uos.remove(path)
        except: pass
        led_blue.off()
        gc.collect()

    def process_audio(self):
        led_red.on()
        global g_audio_chunks, g_audio_written
        gc.collect()
        try:
            self.rec_idx = 0
            self.rec_offset = 0
            g_audio_written = 0
            self.recording = True
            audio.start_streaming(self._audio_callback)
            while self.recording: time.sleep_ms(10)
            audio.stop_streaming()
            led_red.off()
            led_blue.on()

            total_len = 44 + g_audio_written
            self.send_tcp_packet(f"AUD_START:{total_len}\n".encode())

            header = struct.pack('<4sI4s4sIHHIIHH4sI', b'RIFF', 36+g_audio_written, b'WAVE', b'fmt ', 16, 1, CHANNELS, SAMPLE_RATE, SAMPLE_RATE*CHANNELS*BYTES_PER_SAMPLE, CHANNELS*BYTES_PER_SAMPLE, 16, b'data', g_audio_written)
            self.send_tcp_packet(header)

            count_to_send = min(self.rec_idx + 1, len(g_audio_chunks))
            for i in range(count_to_send):
                chunk = g_audio_chunks[i]
                if not chunk: continue
                length = AUDIO_CHUNK_SIZE if i != self.rec_idx else self.rec_offset
                if length > 0:
                    mv = memoryview(chunk)[:length]
                    off = 0
                    while off < length:
                        end = min(off + 1024, length)
                        self.send_tcp_packet(mv[off:end])
                        off = end
        except: pass
        finally:
            self.req_audio = False
            led_red.off()
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

nicla = NiclaSystem()
while True:
    if nicla.trigger_wifi_connect:
        nicla.trigger_wifi_connect = False
        nicla.connect_wifi()

    if g_wifi_connected:
        if not nicla.wlan.isconnected():
            led_green.off()
            g_wifi_connected = False
            nicla.ble.active(True)
            nicla._advertise()
        else:
            is_connected = nicla.manage_connection()
            if is_connected:
                nicla.check_imu()
                if nicla.req_image:
                    nicla.process_image()
                elif nicla.req_audio:
                    nicla.process_audio()
                else:
                    nicla.run_active_detection()
    time.sleep_ms(10)
