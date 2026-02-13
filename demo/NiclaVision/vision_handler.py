import sensor
import image
import ml
import gc
import json
import time

LABELS = ["face", "bottle"]

g_model = None

def init_camera():
    try:
        sensor.reset()
        # Ensure this matches your training: RGB565 and 240x240
        sensor.set_pixformat(sensor.RGB565)
        sensor.set_framesize(sensor.QVGA)
        sensor.set_windowing((240, 240))
        sensor.skip_frames(time=2000)
        print("Camera Init OK (RGB 240x240)")
        return True
    except Exception as e:
        print(f"Cam Init Err: {e}")
        return False

def load_model():
    global g_model
    if g_model is None:
        try:
            g_model = ml.Model("trained")
            print("Model Loaded")
        except Exception as e:
            print(f"Model Load Err: {e}")
            return False
    return True

def capture_image(filename="temp.jpg"):
    gc.collect()
    try:
        sensor.set_windowing((320, 240))
        sensor.skip_frames(time=100)

        img = sensor.snapshot()
        img.save(filename, quality=75)

        sensor.set_windowing((240, 240))
        sensor.skip_frames(time=50)

        return True
    except Exception as e:
        print(f"Snap Err: {e}")
        try:
            sensor.set_windowing((240, 240))
        except:
            pass
        return False

def run_detection():
    global g_model
    gc.collect()

    try:
        if not load_model():
            return json.dumps({"label": "Error", "conf": "No Model"})

        THRESHOLD = 0.6

        sensor.set_windowing((240, 240))

        img = sensor.snapshot()

        result = g_model.predict([img])[0].flatten().tolist()

        best_index = max(range(len(result)), key=lambda i: result[i])
        best_conf = result[best_index]

        label_name = LABELS[best_index] if best_index < len(LABELS) else "Unknown"

        del img
        gc.collect()

        if best_conf >= THRESHOLD:
            return json.dumps({
                "label": label_name,
                "conf": f"{best_conf:.2f}"
            })
        else:
            return json.dumps({
                "label": "Uncertain",
                "conf": f"{best_conf:.2f}"
            })

    except Exception as e:
        gc.collect()
        return json.dumps({"label": "Error", "conf": str(e)})
