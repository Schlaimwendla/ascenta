import bluetooth
import time
import struct
import sensor
import image
import gc # Import garbage collector

# --- UUIDs (Must match Android client) ---
_NICLA_SERVICE_UUID_STR = "12345678-1234-5678-1234-567890ABCDEF"
_NICLA_SERVICE_UUID_RAW = b'\xEF\xCD\xAB\x90\x78\x56\x34\x12\x78\x56\x34\x12\x56\x78\x34\x12'
_NICLA_SERVICE_UUID = bluetooth.UUID(_NICLA_SERVICE_UUID_STR)
_DATA_CHAR_UUID = bluetooth.UUID("12345678-1234-5678-1234-567890ABCDE0")

_DATA_CHAR = (_DATA_CHAR_UUID, bluetooth.FLAG_NOTIFY | bluetooth.FLAG_READ)
_NICLA_SERVICE = (_NICLA_SERVICE_UUID, (_DATA_CHAR,))

# --- BINARY IMAGE SIGNALING ---
START_SEQUENCE = b'\xAA\xBB\xCC\xDD'
END_SEQUENCE   = b'\xDD\xCC\xBB\xAA'

# --- Main peripheral class ---
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
            # Use QQVGA (160x120) for BLE stability
            sensor.set_framesize(sensor.QQVGA)
            sensor.skip_frames(time=2000)
            print("Camera initialized (QVGA).")
        except Exception as e:
            print(f"Error initializing camera: {e}")

    def setup_ble(self):
        self.ble.irq(self._irq)
        ((self.data_handle,),) = self.ble.gatts_register_services((_NICLA_SERVICE,))
        print("BLE Service registered. Data handle:", self.data_handle)

        # Advertising payload for "Nicla"
        short_name = self.name[:5]
        short_name_bytes = short_name.encode('utf-8')
        adv_payload = b'\x02\x01\x06'
        adv_payload += struct.pack('<BB', len(short_name_bytes) + 1, 0x08) + short_name_bytes
        adv_payload += struct.pack('<BB', len(_NICLA_SERVICE_UUID_RAW) + 1, 0x07) + _NICLA_SERVICE_UUID_RAW
        self.adv_payload = adv_payload
        self.ble.gap_advertise(100_000, adv_data=self.adv_payload)
        print(f"Advertising started as '{self.name}' (Advertised as '{short_name}')")

    def _irq(self, event, data):
        if event == 1:
            self.conn_handle, addr_type, addr = data
            self.is_connected = True
            print(f"Connected to central: {addr}")
            gc.collect() # Immediate GC after connection
        elif event == 2:
            self.conn_handle = None
            self.is_connected = False
            print("Disconnected from central.")
            self.ble.gap_advertise(100_000, adv_data=self.adv_payload)
            print(f"Advertising restarted as '{self.name}'")

    def send_data(self, data: bytes):
        """Sends binary data via characteristic notification in 20-byte chunks."""
        if self.conn_handle is not None and self.data_handle is not None:
            CHUNK_SIZE = 20

            for i in range(0, len(data), CHUNK_SIZE):
                chunk = data[i:i + CHUNK_SIZE]
                self.ble.gatts_notify(self.conn_handle, self.data_handle, chunk)
                # --- FIX: Increase chunk delay for memory relief ---
                time.sleep_ms(50)
        else:
            pass

    def capture_and_send_image(self):
        """Captures a JPEG and sends it with start/end markers."""
        try:
            img = sensor.snapshot()

            # Compress to JPEG quality 30 for small file size
            jpeg_buffer = img.compress(quality=30)
            jpeg_bytes = bytes(jpeg_buffer) # Final correct way to get bytes

            print(f"Captured JPEG: {len(jpeg_bytes)} bytes. Sending...")

            # Force garbage collection before the memory-intensive loop
            gc.collect()

            # Send START, Data Chunks, and END sequence
            self.send_data(START_SEQUENCE)
            self.send_data(jpeg_bytes)
            self.send_data(END_SEQUENCE)

            print("Image transmission complete.")

        except Exception as e:
            print(f"Image send error: {e}")


# --- MAIN EXECUTION with Non-Blocking Timer ---
if __name__ == "__main__":
    nicla_ble = BLEPeripheral(name="NiclaVision")

    last_send_time = 0
    SEND_INTERVAL_MS = 3000 # Send image every 3 seconds

    connection_time = 0
    INITIAL_DELAY_MS = 3000 # Initial delay for Android setup

    print("Starting image streaming loop...")
    while True:
        current_time = time.ticks_ms()

        if nicla_ble.is_connected:
            if connection_time == 0:
                # Mark connection time for the initial delay
                connection_time = current_time
                print(f"Connected. Waiting {INITIAL_DELAY_MS // 1000}s for Android setup...")

            # Check if initial delay has passed AND send interval has passed
            if (current_time > connection_time + INITIAL_DELAY_MS) and \
               (current_time > last_send_time + SEND_INTERVAL_MS):

                nicla_ble.capture_and_send_image()
                last_send_time = current_time

        elif not nicla_ble.is_connected:
             connection_time = 0
             last_send_time = 0

        # Small sleep to yield control
        time.sleep_ms(50)
