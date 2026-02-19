package com.example.ascenta

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
// import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.support.image.TensorImage
// import org.tensorflow.lite.task.core.BaseOptions
// import org.tensorflow.lite.task.vision.detector.Detection
// import org.tensorflow.lite.task.vision.detector.ObjectDetector

class ObjectDetectorHelper(
    var threshold: Float = 0.5f,
    var numThreads: Int = 2,
    var maxResults: Int = 1,
    var currentDelegate: Int = DELEGATE_CPU,
    val context: Context,
    val objectDetectorListener: DetectorListener?
) {

    // private var objectDetector: ObjectDetector? = null

    init {
        // setupObjectDetector()
    }

    fun clearObjectDetector() {
        // objectDetector = null
    }

    fun setupObjectDetector() {
        /*
        val optionsBuilder = ObjectDetector.ObjectDetectorOptions.builder()
            .setScoreThreshold(threshold)
            .setMaxResults(maxResults)

        val baseOptionsBuilder = BaseOptions.builder().setNumThreads(numThreads)

        when (currentDelegate) {
            DELEGATE_CPU -> {
            }
            DELEGATE_GPU -> {
                if (CompatibilityList().isDelegateSupportedOnThisDevice) {
                    baseOptionsBuilder.useGpu()
                } else {
                    objectDetectorListener?.onError("GPU not supported on this device. Using CPU.")
                }
            }
        }

        optionsBuilder.setBaseOptions(baseOptionsBuilder.build())

        try {
            objectDetector = ObjectDetector.createFromFileAndOptions(
                context,
                "model.tflite",
                optionsBuilder.build()
            )
        } catch (e: IllegalStateException) {
            objectDetectorListener?.onError("Object detector failed to initialize. See error logs for details")
        }*/
    }

    fun detect(image: Bitmap, imageRotation: Int) {
        /*
        if (objectDetector == null) {
            setupObjectDetector()
        }

        var inferenceTime = SystemClock.uptimeMillis()

        val imageProcessor = org.tensorflow.lite.support.image.ImageProcessor.Builder()
            .add(org.tensorflow.lite.support.image.ops.Rot90Op(-imageRotation / 90))
            .build()

        val tensorImage = imageProcessor.process(TensorImage.fromBitmap(image))

        val results = objectDetector?.detect(tensorImage)
        inferenceTime = SystemClock.uptimeMillis() - inferenceTime

        objectDetectorListener?.onResults(
            results,
            inferenceTime,
            tensorImage.height,
            tensorImage.width
        )
        */
    }

    interface DetectorListener {
        fun onError(error: String)
        fun onResults(
            results: MutableList<Any>?,
            inferenceTime: Long,
            imageHeight: Int,
            imageWidth: Int
        )
    }

    companion object {
        const val DELEGATE_CPU = 0
        const val DELEGATE_GPU = 1
    }
}
