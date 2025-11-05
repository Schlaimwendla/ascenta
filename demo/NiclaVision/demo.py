import bluetooth
import time
import struct
import sensor
import image
import gc
import uos

_NICLA_SERVICE_UUID_STR = "12345678-1234-5678-1234-567890ABCDEF"
_NICLA_SERVICE_UUID = bluetooth.UUID(_NICLA_SERVICE_UUID_STR)
_DATA_CHAR_UUID = bluetooth.UUID("12345678-1234-5678-1234-567890ABCDE0")

_COMMAND_SERVICE_UUID = bluetooth.UUID("80000000-0000-1000-8000-00805f9b34fb")
_COMMAND_CHAR_UUID = bluetooth.UUID("80000001-0000-1000-8000-00805f9b34fb")

_DATA_CHAR = (_DATA_CHAR_UUID, bluetooth.FLAG_NOTIFY | bluetooth.FLAG_READ)
_NICLA_SERVICE = (_NICLA_SERVICE_UUID, (_DATA_CHAR,))

_COMMAND_CHAR = (_COMMAND_CHAR_UUID, bluetooth.FLAG_WRITE)
_COMMAND_SERVICE = (_COMMAND_SERVICE_UUID, (_COMMAND_CHAR,))

START_SEQUENCE = b'\xAA\xBB\xCC\xDD'
END_SEQUENCE = b'\xDD\xCC\xBB\xAA'

class BLEPeripheral:
    def __init__(self, name="NiclaVision"):
        self.ble = bluetooth.BLE()
        self.ble.active(True)
        self.name = name
        self.conn_handle = None
        self.data_handle = None
        self.command_handle = None
        self.is_connected = False
        self.adv_payload = b''
        self.stream_enabled = True

        self._init_camera()
        self.setup_ble()

        gc.collect()

    def _init_camera(self):
        try:
            sensor.reset()
            sensor.set_pixformat(sensor.RGB565)
            sensor.set_framesize(sensor.QVGA)
            sensor.skip_frames(time=2000)
            print("Camera initialized (QVGA).")
        except Exception as e:
            print(f"Error initializing camera: {e}")

    def setup_ble(self):
        self.ble.irq(self._irq)
        ((self.data_handle,), (self.command_handle,)) = self.ble.gatts_register_services((_NICLA_SERVICE, _COMMAND_SERVICE))
        print("BLE Services registered. Image Handle:", self.data_handle, "Command Handle:", self.command_handle)

        short_name = "Nicla"
        short_name_bytes = short_name.encode('utf-8')

        adv_payload = b'\x02\x01\x06'
        adv_payload += struct.pack('<BB', len(short_name_bytes) + 1, 0x09) + short_name_bytes

        self.adv_payload = adv_payload
        self.ble.gap_advertise(100_000, adv_data=self.adv_payload)
        print(f"Advertising started as '{short_name}'")

    def handle_command(self, command):
        command_lower = command.lower()
        print(f"Received command: {command}")

        if command_lower == "stop stream":
            self.stream_enabled = False
            print("Action: Stopping image stream.")
        elif command_lower == "start stream":
            self.stream_enabled = True
            print("Action: Resuming image stream.")
        elif command_lower == "take_picture" and self.is_connected:
            print("Action: Capturing and sending single picture...")
            self.capture_and_send_image()
        else:
            print(f"Unknown command or not connected: {command}")

        gc.collect()

    def _irq(self, event, data):
        if event == 1:
            self.conn_handle, addr_type, addr = data
            self.is_connected = True
            self.stream_enabled = True
            print(f"Connected to central: {addr}")
            gc.collect()
        elif event == 2:
            self.conn_handle = None
            self.is_connected = False
            self.stream_enabled = False
            print("Disconnected from central.")
            self.ble.gap_advertise(100_000, adv_data=self.adv_payload)
            print(f"Advertising restarted as 'Nicla'")
        elif event == 3:
            conn_handle, value_handle, response_needed = data
            if value_handle == self.command_handle:
                command_bytes = self.ble.gatts_read(self.command_handle)
                try:
                    command = command_bytes.decode('utf-8').strip()
                    self.handle_command(command)
                except UnicodeError:
                    print("Received non-text command data.")
            gc.collect()

    def send_data(self, data: bytes):
        if self.conn_handle is not None and self.data_handle is not None:
            CHUNK_SIZE = 20

            for i in range(0, len(data), CHUNK_SIZE):
                chunk = data[i:i + CHUNK_SIZE]
                self.ble.gatts_notify(self.conn_handle, self.data_handle, chunk)
                time.sleep_ms(8)
        else:
            pass

    def capture_and_send_image(self):
        FILE_PATH = "temp_image.jpg"

        try:
            img = sensor.snapshot()
            # OPTIMIZATION 1: Drastically lowered quality to reduce file size (and thus, packet count)
            img.save(str(FILE_PATH), quality=75)

            del img
            gc.collect()

            with open(FILE_PATH, 'rb') as f:
                file_size = f.seek(0, 2)
                f.seek(0)
                print(f"Captured JPEG (QVGA, Q75) saved to flash: {file_size} bytes. Sending...")

                self.send_data(START_SEQUENCE)

                while True:
                    read_chunk = f.read(128)
                    if not read_chunk:
                        break

                    self.send_data(read_chunk)
                    # OPTIMIZATION 2: Increased outer delay to 20ms
                    time.sleep_ms(20)

                self.send_data(END_SEQUENCE)
                print("Image transmission complete.")

            uos.remove(FILE_PATH)
            print(f"Temporary file {FILE_PATH} deleted.")

        except Exception as e:
            print(f"Image transfer error: {e}")
            gc.collect()


if __name__ == "__main__":
    nicla_ble = BLEPeripheral(name="NiclaVision")

    last_send_time = 0
    SEND_INTERVAL_MS = 3000

    connection_time = 0
    INITIAL_DELAY_MS = 3000

    print("Starting image streaming loop...")
    while True:
        current_time = time.ticks_ms()

        if nicla_ble.is_connected and nicla_ble.stream_enabled:
            if connection_time == 0:
                connection_time = current_time
                print(f"Connected. Waiting {INITIAL_DELAY_MS // 1000}s for central setup...")

            if (current_time > connection_time + INITIAL_DELAY_MS) and \
               (current_time > last_send_time + SEND_INTERVAL_MS):

                nicla_ble.capture_and_send_image()
                last_send_time = current_time

        elif not nicla_ble.is_connected:
              connection_time = 0
              last_send_time = 0

        gc.collect()
        time.sleep_ms(50)
