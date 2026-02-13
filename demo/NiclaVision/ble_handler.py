import bluetooth
import struct
import time
import config
from pyb import LED
import micropython

micropython.alloc_emergency_exception_buf(100)
led_green = LED(2)

class BLEHandler:
    def __init__(self):
        self.ble = bluetooth.BLE()
        self.ble.active(True)

        self.conn_handle = None
        self.payload_size = 20

        self.req_image = False
        self.req_audio_rec = False
        self.req_detect = False

        self._setup_services()
        self._advertise()

    def _setup_services(self):
        self.ble.irq(self._irq)
        vision_svc = (config.NICLA_SERVICE_UUID, (
            (config.DATA_CHAR_UUID, bluetooth.FLAG_NOTIFY | bluetooth.FLAG_READ),
        ))
        cmd_svc = (config.CMD_SERVICE_UUID, (
            (config.CMD_CHAR_UUID, bluetooth.FLAG_WRITE),
        ))
        uart_svc = (config.UART_SERVICE_UUID, (
            (config.UART_TX_UUID, bluetooth.FLAG_NOTIFY | bluetooth.FLAG_READ),
            (config.UART_RX_UUID, bluetooth.FLAG_WRITE),
        ))

        handles = self.ble.gatts_register_services((vision_svc, cmd_svc, uart_svc))

        self.h_img_data = handles[0][0]
        self.h_cmd      = handles[1][0]
        self.h_uart_tx  = handles[2][0]
        self.h_uart_rx  = handles[2][1]

    def _advertise(self):
        name = b"Nicla"
        adv = b'\x02\x01\x06' + struct.pack('<BB', len(name)+1, 0x09) + name
        self.ble.gap_advertise(100000, adv_data=adv)
        led_green.on()

    def _irq(self, event, data):
        if event == 1:
            self.conn_handle = data[0]
            led_green.off()
            print("BLE: Connected")

        elif event == 2:
            self.conn_handle = None
            self.payload_size = 20
            self._advertise()
            print("BLE: Disconnected")

        elif event == 3:
            conn, value_handle = data
            if value_handle == self.h_cmd:
                self._handle_cmd(self.h_cmd, "image")
            elif value_handle == self.h_uart_rx:
                self._handle_cmd(self.h_uart_rx, "uart")

        elif event == 21:
            self.payload_size = data[1] - 3
            print(f"MTU: {data[1]}")

    def _handle_cmd(self, handle, type):
        try:
            cmd = self.ble.gatts_read(handle).decode().strip()
            print(f"CMD: {cmd}")
            if cmd == "take_picture": self.req_image = True
            elif cmd == "RECORD": self.req_audio_rec = True
            elif cmd == "START_DETECT": self.req_detect = True
        except: pass

    def send_image_packet(self, data):
        self._send_fast(self.h_img_data, data)

    def send_uart_packet(self, data):
        self._send_fast(self.h_uart_tx, data)

    def _send_fast(self, handle, data):
        if not self.conn_handle: return
        try:
            self.ble.gatts_notify(self.conn_handle, handle, data)
        except OSError as e:
            if e.args[0] == 11:
                time.sleep_ms(10)
                try:
                    self.ble.gatts_notify(self.conn_handle, handle, data)
                except: pass
