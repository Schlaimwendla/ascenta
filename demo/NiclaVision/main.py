import bluetooth, network, socket, struct, time, sensor, image, gc, uos, audio, micropython, ml, os
from machine import I2C, Pin, SPI
import vl53l1x
from lsm6dsox import LSM6DSOX
from pyb import LED

micropython.alloc_emergency_exception_buf(100)
led_red, led_green, led_blue = LED(1), LED(2), LED(3)

TCP_PORT, UDP_DISC_PORT = 5005, 5006
UDP_IP = "255.255.255.255"
REC_SECONDS, SAMPLE_RATE = 2.5, 16000
TOTAL_AUDIO_BYTES = int(SAMPLE_RATE * 1 * 2 * REC_SECONDS)
AUDIO_CHUNK_SIZE = 1024
NUM_AUDIO_CHUNKS = (TOTAL_AUDIO_BYTES // AUDIO_CHUNK_SIZE) + 1
FUEL_GAUGE_ADDR = 0x36

TOF_WARN_DIST = 1500
TOF_ALERT_DIST = 800
TOF_CRITICAL_DIST = 400
TOF_POLL_MS = 150

PROV_SERVICE_UUID = bluetooth.UUID("12345678-1234-1234-1234-123456789abc")
SSID_CHAR_UUID = bluetooth.UUID("12345678-1234-1234-1234-123456789abd")
PASS_CHAR_UUID = bluetooth.UUID("12345678-1234-1234-1234-123456789abe")

g_audio_chunks = []
g_audio_written = 0
g_wifi_connected = False
g_ssid, g_pass = "", ""

class NiclaSystem:
    def __init__(self):
        self.server_sock = None
        self.tcp_conn = None
        self.last_broadcast = 0
        self.last_bat_check = 0
        self.wlan = None
        self.ble = bluetooth.BLE()
        self.ble.active(True)
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
        self.last_tap_time = 0
        self.tap_count = 0
        self.tap_settle_time = 0
        self.last_accel = (0, 0, 0)
        self.last_tof_poll = 0
        self.last_tof_zone = "clear"

        self._init_hardware()

        global g_audio_chunks
        try:
            gc.collect()
            g_audio_chunks = [bytearray(AUDIO_CHUNK_SIZE) for _ in range(NUM_AUDIO_CHUNKS)]
            print("DEBUG [Audio]: Buffer allocated")
        except: print("DEBUG [Audio]: Alloc Failed")

        saved = self._load_config()
        if saved:
            global g_ssid, g_pass
            g_ssid, g_pass = saved
            self.trigger_wifi_connect = True
        else: self._setup_ble_provisioning()

    def _init_hardware(self):
        print("DEBUG [System]: Init Start")
        try:
            sensor.reset()
            sensor.set_pixformat(sensor.RGB565)
            sensor.set_framesize(sensor.QVGA)
            sensor.set_windowing((240, 240))
            sensor.skip_frames(time=2000)
            print("DEBUG [Sensor]: OK")
        except: print("DEBUG [Sensor]: FAIL")

        try:
            i2c_bus = I2C(2)
            scan = i2c_bus.scan()
            if 0x29 in scan:
                self.tof = vl53l1x.VL53L1X(i2c_bus) # Basic OpenMV driver
                print("DEBUG [ToF]: OK")
            if FUEL_GAUGE_ADDR in scan:
                self.fuel_gauge_i2c = i2c_bus
                print("DEBUG [Fuel]: OK")
        except: print("DEBUG [I2C]: FAIL")

        try:
            self.lsm = LSM6DSOX(SPI(5), cs=Pin("PF6", Pin.OUT_PP, Pin.PULL_UP))
            print("DEBUG [IMU]: OK")
        except: self.lsm = None

        try:
            audio.init(channels=1, frequency=SAMPLE_RATE, gain_db=24)
            self.net = ml.Model("trained.tflite", load_to_fb=True)
            self.labels = [line.rstrip('\n') for line in open("labels.txt")]
            print("DEBUG [ML]: OK")
        except: print("DEBUG [ML]: FAIL")

    def get_soc(self):
        if not self.fuel_gauge_i2c: return None
        try:
            v_raw = self.fuel_gauge_i2c.readfrom_mem(FUEL_GAUGE_ADDR, 0x09, 2)
            voltage = int.from_bytes(v_raw, 'little') * 0.000078125
            s_raw = self.fuel_gauge_i2c.readfrom_mem(FUEL_GAUGE_ADDR, 0x06, 2)
            soc = int.from_bytes(s_raw, 'little') / 256.0
            if voltage > 4.15: soc = 100.0 # Full charge calibration
            return soc
        except: return None

    def check_imu(self):
        if not self.lsm or self.req_audio or self.req_image: return
        try:
            x, y, z = self.lsm.accel()
            dz = abs(z - self.last_accel[2])
            self.last_accel = (x, y, z)
            now = time.ticks_ms()

            # Resolve pending tap gesture after settle window
            if self.tap_count > 0 and time.ticks_diff(now, self.last_tap_time) > 600:
                if self.tap_count == 1: # Double Tap
                    print("DEBUG [IMU]: RECORDING")
                    self.req_audio = True
                elif self.tap_count >= 2: # Triple Tap
                    print("DEBUG [IMU]: BATTERY")
                    soc = self.get_soc()
                    msg = '{"type":"bat_status","soc":"%.1f"}\n' % (soc if soc else 0)
                    self.send_tcp_packet(msg.encode())
                self.tap_count = 0

            if dz > 4.5: # Hard tap detection
                print(f"DEBUG [IMU]: Spike {dz:.2f}")
                diff = time.ticks_diff(now, self.last_tap_time)
                if 100 < diff < 600:
                    self.tap_count += 1
                else:
                    self.tap_count = 0
                self.last_tap_time = now
        except Exception as e: print("DEBUG [IMU]: Error", e)

    def check_tof(self):
        if not self.tof or self.req_image or self.req_audio: return
        now = time.ticks_ms()
        if time.ticks_diff(now, self.last_tof_poll) < TOF_POLL_MS: return
        self.last_tof_poll = now
        try:
            dist = self.tof.read()
            if dist <= 0 or dist > 8000: return  # Out of range / bad read

            if dist <= TOF_CRITICAL_DIST:
                zone = "critical"
            elif dist <= TOF_ALERT_DIST:
                zone = "alert"
            elif dist <= TOF_WARN_DIST:
                zone = "warning"
            else:
                zone = "clear"

            if zone != self.last_tof_zone:
                self.last_tof_zone = zone
                if zone != "clear":
                    msg = '{"type":"collision","dist":%d,"zone":"%s"}\n' % (dist, zone)
                    print(f"DEBUG [ToF]: {zone} at {dist}mm")
                    self.send_tcp_packet(msg.encode())
                else:
                    self.send_tcp_packet(b'{"type":"collision","dist":0,"zone":"clear"}\n')
        except Exception as e: print("DEBUG [ToF]: Error", e)

    def run_active_detection(self):
        if not self.net or self.req_image or self.req_audio: return
        now = time.ticks_ms()

        if self.tcp_conn:
            if not self.sent_initial_bat and time.ticks_diff(now, self.connection_time) > 2000:
                soc = self.get_soc()
                if soc: self.send_tcp_packet(('{"type":"bat_init","soc":"%.1f"}\n'%soc).encode())
                self.sent_initial_bat = True

            if time.ticks_diff(now, self.last_bat_check) > 60000:
                self.last_bat_check = now
                soc = self.get_soc()
                if soc and soc <= 20.0 and not self.low_bat_warned:
                    self.send_tcp_packet(b'{"type":"bat_warn","soc":"20.0"}\n')
                    self.low_bat_warned = True

        try:
            img = sensor.snapshot()
            raw_output = self.net.predict([img])
            heatmap = raw_output[0]

            best_label, best_conf, best_x = None, 0.0, 0
            if len(heatmap.shape) == 4:
                grid_x, classes = heatmap.shape[2], heatmap.shape[3]
                cell_w = img.width() / grid_x
                for y in range(heatmap.shape[1]):
                    for x in range(grid_x):
                        for c in range(1, classes):
                            score = heatmap[0][y][x][c]
                            if score > 0.45 and score > best_conf:
                                best_conf, best_x = score, int(x*cell_w+cell_w/2)
                                best_label = self.labels[c] if self.labels else str(c)

            if best_label:
                pos = "left" if best_x < 80 else "right" if best_x > 160 else "straight"
                distance = self.tof.read() if self.tof else 0 # Simple read

                res = '{"type":"det","label":"%s","dist":%d,"pos":"%s"}\n' % (best_label, distance, pos)
                print(f"DEBUG [ML]: Sent {best_label} at {distance}mm")
                self.send_tcp_packet(res.encode())
        except Exception as e: print("DEBUG [ML]: Error", e)
        finally: gc.collect()

    def process_image(self):
        print("DEBUG [Image]: Snap")
        self.req_image = False
        led_blue.on()
        try:
            img = sensor.snapshot()
            img.save("temp.jpg", quality=40)
            size = os.stat("temp.jpg")[6]
            self.send_tcp_packet(f"IMG_START:{size}\n".encode())
            with open("temp.jpg", 'rb') as f:
                while True:
                    chunk = f.read(1024)
                    if not chunk: break
                    self.send_tcp_packet(chunk)
            print("DEBUG [Image]: Sent")
        except Exception as e: print("DEBUG [Image]: Error", e)
        finally:
            try: uos.remove("temp.jpg")
            except: pass
            led_blue.off(); gc.collect()

    def process_audio(self):
        print("DEBUG [Audio]: Rec Start")
        led_red.on()
        global g_audio_chunks, g_audio_written
        gc.collect()
        try:
            self.rec_idx, self.rec_offset, g_audio_written = 0, 0, 0
            self.recording = True
            audio.start_streaming(self._audio_callback)
            while self.recording: time.sleep_ms(10)
            audio.stop_streaming()
            led_red.off(); led_blue.on()

            total_len = 44 + g_audio_written
            self.send_tcp_packet(f"AUD_START:{total_len}\n".encode())
            header = struct.pack('<4sI4s4sIHHIIHH4sI', b'RIFF', 36+g_audio_written, b'WAVE', b'fmt ', 16, 1, 1, SAMPLE_RATE, SAMPLE_RATE*2, 2, 16, b'data', g_audio_written)
            self.send_tcp_packet(header)

            for i in range(min(self.rec_idx + 1, len(g_audio_chunks))):
                chunk = g_audio_chunks[i]
                length = AUDIO_CHUNK_SIZE if i != self.rec_idx else self.rec_offset
                if chunk and length > 0:
                    mv = memoryview(chunk)[:length]
                    off = 0
                    while off < length:
                        end = min(off + 1024, length)
                        self.send_tcp_packet(mv[off:end])
                        off = end
            print("DEBUG [Audio]: Sent")
        except: pass
        finally: self.req_audio = False; led_red.off(); led_blue.off(); gc.collect()

    def _audio_callback(self, buf):
        global g_audio_written
        if not self.recording: return
        mv = memoryview(buf)
        l, off = len(mv), 0
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
            if self.rec_offset >= AUDIO_CHUNK_SIZE: self.rec_idx += 1; self.rec_offset = 0
        if g_audio_written >= TOTAL_AUDIO_BYTES: self.recording = False

    def send_tcp_packet(self, data):
        if not self.tcp_conn: return
        try:
            self.tcp_conn.setblocking(True)
            self.tcp_conn.sendall(data)
            self.tcp_conn.setblocking(False)
        except: self.tcp_conn.close(); self.tcp_conn = None

    def _setup_ble_provisioning(self):
        self.ble.irq(self._ble_irq)
        svc = (PROV_SERVICE_UUID, ((SSID_CHAR_UUID, bluetooth.FLAG_WRITE), (PASS_CHAR_UUID, bluetooth.FLAG_WRITE)))
        h = self.ble.gatts_register_services((svc,))
        self.h_ssid, self.h_pass = h[0][0], h[0][1]
        self._advertise()

    def _advertise(self):
        adv = b'\x02\x01\x06' + struct.pack('<BB', 6, 0x09) + b"Nicla"
        self.ble.gap_advertise(100000, adv_data=adv)
        led_blue.on()

    def _ble_irq(self, event, data):
        global g_ssid, g_pass
        if event == 1: led_blue.off()
        elif event == 3:
            c, v = data
            if v == self.h_ssid: g_ssid = self.ble.gatts_read(self.h_ssid).decode().strip()
            elif v == self.h_pass:
                g_pass = self.ble.gatts_read(self.h_pass).decode().strip()
                if g_ssid and g_pass: self._save_config(g_ssid, g_pass); self.trigger_wifi_connect = True

    def connect_wifi(self):
        global g_ssid, g_pass, g_wifi_connected
        print(f"DEBUG [WiFi]: Connecting to {g_ssid}")
        self.ble.active(False)
        self.wlan = network.WLAN(network.STA_IF)
        self.wlan.active(True)
        self.wlan.connect(g_ssid, g_pass)
        for _ in range(15):
            if self.wlan.isconnected(): break
            led_red.on(); time.sleep_ms(100); led_red.off(); time.sleep_ms(900)
        if self.wlan.isconnected():
            g_wifi_connected = True
            led_green.on()
            print("DEBUG [WiFi]: IP", self.wlan.ifconfig()[0])
            self._setup_tcp_server()
        else:
            try: uos.remove("wifi.txt")
            except: pass
            self.ble.active(True); self._setup_ble_provisioning()

    def _setup_tcp_server(self):
        self.server_sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.server_sock.bind(('', TCP_PORT))
        self.server_sock.listen(1)
        self.server_sock.setblocking(False)

    def manage_connection(self):
        if not self.tcp_conn:
            try:
                conn, addr = self.server_sock.accept()
                conn.setblocking(False)
                self.tcp_conn = conn
                self.connection_time = time.ticks_ms()
                self.sent_initial_bat = False
                self.low_bat_warned = False
                self.last_tof_zone = "clear"
                print("DEBUG [TCP]: Connected")
            except:
                if time.ticks_diff(time.ticks_ms(), self.last_broadcast) > 2000:
                    try:
                        u = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
                        u.sendto(b"NICLA_READY", (UDP_IP, UDP_DISC_PORT))
                        u.close()
                    except: pass
                    self.last_broadcast = time.ticks_ms()
            return False

        try:
            d = self.tcp_conn.recv(128)
            if d:
                lines = d.decode().strip().split('\n')
                for l in lines:
                    c = l.strip()
                    if c == "take_picture": self.req_image = True
                    elif c == "RECORD": self.req_audio = True
                    elif c == "ping": self.send_tcp_packet(b"pong\n")
            elif d == b'': self.tcp_conn.close(); self.tcp_conn = None
        except: pass
        return True

    def _save_config(self, s, p):
        try:
            with open("wifi.txt", "w") as f: f.write(s+"\n"+p)
        except: pass

    def _load_config(self):
        try:
            with open("wifi.txt", "r") as f:
                l = f.readlines()
                return l[0].strip(), l[1].strip()
        except: return None

nicla = NiclaSystem()
while True:
    if nicla.trigger_wifi_connect:
        nicla.trigger_wifi_connect = False
        nicla.connect_wifi()
    if g_wifi_connected:
        if not nicla.wlan.isconnected():
            g_wifi_connected = False
            nicla.ble.active(True); nicla._setup_ble_provisioning()
        else:
            if nicla.manage_connection():
                nicla.check_imu()
                nicla.check_tof()
                if nicla.req_image: nicla.process_image()
                elif nicla.req_audio: nicla.process_audio()
                else: nicla.run_active_detection()
    time.sleep_ms(10)
