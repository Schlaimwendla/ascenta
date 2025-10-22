from machine import Pin
import time, bluetooth, struct, sensor, image, gc

# --- BUTTON + INTERRUPT ---
pressed = False
last_press = 0

def button_handler(pin):
    global pressed, last_press
    now = time.ticks_ms()
    if time.ticks_diff(now, last_press) > 200:  # debounce 200 ms
        pressed = True
        last_press = now
        print("Button pressed.")

button = Pin('A1', Pin.IN, Pin.PULL_UP)
button.irq(trigger=Pin.IRQ_FALLING, handler=button_handler)

# --- BLE setup ---
_NICLA_SERVICE_UUID_STR = "12345678-1234-5678-1234-567890ABCDEF"
_NICLA_SERVICE_UUID_RAW = b'\xEF\xCD\xAB\x90\x78\x56\x34\x12\x78\x56\x34\x12\x56\x78\x34\x12'
_NICLA_SERVICE_UUID = bluetooth.UUID(_NICLA_SERVICE_UUID_STR)
_DATA_CHAR_UUID = bluetooth.UUID("12345678-1234-5678-1234-567890ABCDE0")

_DATA_CHAR = (_DATA_CHAR_UUID, bluetooth.FLAG_NOTIFY | bluetooth.FLAG_READ)
_NICLA_SERVICE = (_NICLA_SERVICE_UUID, (_DATA_CHAR,))

START_SEQUENCE = b'\xAA\xBB\xCC\xDD'
END_SEQUENCE   = b'\xDD\xCC\xBB\xAA'

class BLEPeripheral:
    def __init__(self, name="NiclaVision"):
        self.ble = bluetooth.BLE()
        self.ble.active(True)
        self.name = name
        self.conn_handle = None
        self.data_handle = None
        self.is_connected = False
        self._init_camera()
        self.setup_ble()

    def _init_camera(self):
        try:
            sensor.reset()
            sensor.set_pixformat(sensor.RGB565)
            sensor.set_framesize(sensor.QQVGA)
            sensor.skip_frames(time=2000)
            print("Camera initialized (QVGA).")
        except Exception as e:
            print(f"Camera init error: {e}")

    def setup_ble(self):
        self.ble.irq(self._irq)
        ((self.data_handle,),) = self.ble.gatts_register_services((_NICLA_SERVICE,))
        short_name = self.name[:5].encode('utf-8')
        adv = b'\x02\x01\x06'
        adv += struct.pack('<BB', len(short_name) + 1, 0x08) + short_name
        adv += struct.pack('<BB', len(_NICLA_SERVICE_UUID_RAW) + 1, 0x07) + _NICLA_SERVICE_UUID_RAW
        self.adv_payload = adv
        self.ble.gap_advertise(100_000, adv_data=adv)
        print("BLE advertising started.")

    def _irq(self, event, data):
        if event == 1:
            self.conn_handle, *_ = data
            self.is_connected = True
            print("Central connected.")
        elif event == 2:
            self.conn_handle = None
            self.is_connected = False
            print("Central disconnected.")
            self.ble.gap_advertise(100_000, adv_data=self.adv_payload)

    def send_data(self, data: bytes):
        if self.conn_handle is None or self.data_handle is None:
            return
        CHUNK = 253
        for i in range(0, len(data), CHUNK):
            self.ble.gatts_notify(self.conn_handle, self.data_handle, data[i:i+CHUNK])
            time.sleep_ms(20)

    def capture_and_send_image(self):
        try:
            gc.collect()

            # Reinitialize camera to clear internal buffers
            sensor.reset()
            sensor.set_pixformat(sensor.RGB565)
            sensor.set_framesize(sensor.QQVGA)   # lower res avoids fragmentation
            sensor.skip_frames(time=500)
            gc.collect()

            img = sensor.snapshot()
            jpeg = bytes(img.compress(quality=30))
            size = len(jpeg)
            print("Captured, sending", size, "bytes.")
            del img
            gc.collect()

            # Temporarily stop advertising to free BLE RAM
            self.ble.gap_advertise(None)
            self.send_data(START_SEQUENCE)
            gc.collect()

            CHUNK = 253
            for i in range(0, size, CHUNK):
                self.ble.gatts_notify(self.conn_handle, self.data_handle, jpeg[i:i+CHUNK])
                time.sleep_ms(20)
                if i % (CHUNK * 10) == 0:
                    gc.collect()

            self.send_data(END_SEQUENCE)
            print("Send complete.")
            del jpeg
            gc.collect()

            # Resume advertising after send
            self.ble.gap_advertise(100_000, adv_data=self.adv_payload)
            gc.collect()

        except MemoryError:
            print("MemoryError: reduce resolution or reboot to defragment.")
            gc.collect()
        except Exception as e:
            print("Image send error:", e)
            gc.collect()



# --- MAIN LOOP ---
if __name__ == "__main__":
    nicla_ble = BLEPeripheral("NiclaVision")
    print("Waiting for button interrupt...")

    while True:
        if pressed:
            pressed = False
            if nicla_ble.is_connected:
                nicla_ble.capture_and_send_image()
            else:
                print("No BLE connection, skipping capture.")
        time.sleep_ms(50)
