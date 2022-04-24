package it.dani.cameraapp.view

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.*
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.app.ActivityCompat
import androidx.lifecycle.LifecycleOwner
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import it.dani.cameraapp.R
import it.dani.cameraapp.camera.*
import it.dani.cameraapp.view.utils.PermissionUtils
import java.lang.StringBuilder
import java.util.concurrent.Executors

/**
 * @author Daniele
 *
 * This class display what camera is viewing
 */

class EyeTrackingActivity : AppCompatActivity() {

    /**
     * @property[beenAskedPermission] Remember if is the first time asking for permissions
     */
    private var beenAskedPermission = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        this.setContentView(R.layout.eye_tracking_activity)
    }

    override fun onResume() {
        super.onResume()

        findViewById<ExtendedFloatingActionButton>(R.id.preview_button).apply {
            text = this@EyeTrackingActivity.resources.getString(R.string.preview_image_button_start)
        }

        findViewById<ExtendedFloatingActionButton>(R.id.analyze_button).apply {
            text = this@EyeTrackingActivity.resources.getString(R.string.analyze_image_button_start)
        }

        if(!PermissionUtils.permissionGranted(this, arrayOf(Manifest.permission.CAMERA))) {
            if(!this.beenAskedPermission) {
                this.beenAskedPermission = true
                ActivityCompat.requestPermissions(this,arrayOf(Manifest.permission.CAMERA),0xA1)
            }
        } else {
            CameraManager.provideCamera(this,this::bindPreview)
        }
    }

    override fun onPause() {
        super.onPause()
        CameraManager.undoCamera(this)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        when(requestCode) {
            0xA1 -> {
                var flagOK = true
                for(countRes in grantResults.indices) {
                    if(grantResults[countRes] != PackageManager.PERMISSION_GRANTED) {
                        flagOK = false
                    }
                }

                if(flagOK) {
                    CameraManager.provideCamera(this,this::bindPreview)
                } else {
                    Toast.makeText(this, R.string.permission_camera_denied, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * This method remove all camera uses and close the camera
     */
    private fun bindPreview(cameraProvider: ProcessCameraProvider) {

        val previewView = this.findViewById<PreviewView>(R.id.preview_view)

        var isPreviewActive = false
        var isAnalyzeActive = false

        var cameraSelected = CameraSelector.LENS_FACING_FRONT

        var cameraSelector = CameraSelector.Builder()
            .requireLensFacing(cameraSelected)
            .build()

        val preview = Preview.Builder().build()
        preview.setSurfaceProvider(previewView.surfaceProvider)

        val analysis = ImageAnalysis.Builder()
            .setTargetRotation(previewView.display.rotation)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        val adjustFrontFunc = { boundingBox : BoundingBox -> BoundingBox(1.0f - boundingBox.right, boundingBox.top,1.0f - boundingBox.left,boundingBox.bottom) }
        val adjustDflFunc = { boundingBox : BoundingBox -> boundingBox }
        var adjustFunc = when(cameraSelected) {
            CameraSelector.LENS_FACING_FRONT -> adjustFrontFunc
            else -> adjustDflFunc
        }

        val analyzer : ObjectDetection = NewEyeTrackingDetector(this).apply {
            onSuccess += {
                runOnUiThread {
                    this@EyeTrackingActivity.manageAnalyzedObjs(it,adjustFunc)
                }
            }

            var handler : (Int,Int) -> Unit = {_,_->}
            handler = { width,height ->
                findViewById<View>(R.id.eye_tracking_view).also {
                    var handlerView = {}
                    handlerView = {
                        findViewById<View>(R.id.analyze_view).apply {
                            val params = layoutParams as ConstraintLayout.LayoutParams
                            val screenWidth = it.measuredWidth
                            params.height = (((height * 1.0f) / width) * screenWidth).toInt()
                            layoutParams = params
                        }
                        it.viewTreeObserver.removeOnGlobalLayoutListener(handlerView)
                        //Log.i("View","Correctly resized analyze view, handler deleted")
                        //TODO l'handler non viene mai rimosso e viene invocato sempre possibile memory leak
                    }
                    it.viewTreeObserver.addOnGlobalLayoutListener(handlerView)
                }
                onGiveImageSize -= handler
            }
            onGiveImageSize += handler
        }

        findViewById<Button>(R.id.preview_button).apply {
            setOnClickListener {
                isPreviewActive = !isPreviewActive
                val buttonText = if(isPreviewActive) R.string.preview_image_button_stop else R.string.preview_image_button_start
                this.setText(buttonText)

                if(!isPreviewActive) {
                    isAnalyzeActive = false

                    cameraProvider.unbindAll()
                } else {
                    cameraProvider.bindToLifecycle(this@EyeTrackingActivity as LifecycleOwner,cameraSelector,preview)
                }
            }
        }

        findViewById<Button>(R.id.analyze_button).apply {
            setOnClickListener {
                if(isPreviewActive) {
                    isAnalyzeActive = !isAnalyzeActive
                    val buttonText = if(isAnalyzeActive) R.string.analyze_image_button_stop else R.string.analyze_image_button_start
                    this.setText(buttonText)

                    if(!isAnalyzeActive) {
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(this@EyeTrackingActivity as LifecycleOwner,cameraSelector,preview)
                    } else {
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(this@EyeTrackingActivity as LifecycleOwner,cameraSelector,preview,analysis)
                    }
                }
            }
        }

        findViewById<FloatingActionButton>(R.id.switch_camera_button).apply {
            setOnClickListener {
                cameraProvider.unbindAll()

                val newCameraSelector = CameraSelector.Builder()
                    .requireLensFacing(
                        when(cameraSelected) {
                            CameraSelector.LENS_FACING_FRONT -> {
                                cameraSelected = CameraSelector.LENS_FACING_BACK
                                adjustFunc = adjustDflFunc
                                cameraSelected
                        }
                        CameraSelector.LENS_FACING_BACK -> {
                            cameraSelected = CameraSelector.LENS_FACING_FRONT
                            adjustFunc = adjustFrontFunc
                            cameraSelected
                        }
                        else -> {
                            cameraSelected = CameraSelector.LENS_FACING_FRONT
                            adjustFunc = adjustFrontFunc
                            cameraSelected
                        }
                    }).build()

                cameraSelector = newCameraSelector
                if(isAnalyzeActive) {
                    cameraProvider.bindToLifecycle(this@EyeTrackingActivity as LifecycleOwner,cameraSelector,preview,analysis)
                } else if(isPreviewActive) {
                    cameraProvider.bindToLifecycle(this@EyeTrackingActivity as LifecycleOwner,cameraSelector,preview)
                }
            }
        }

        analysis.setAnalyzer(Executors.newSingleThreadExecutor(),analyzer)

        cameraProvider.unbindAll()
    }

    private fun getAnalyzeColor(i : Int) : Int {
        return when(i%5) {
            0 -> Color.RED
            1 -> Color.BLUE
            2 -> Color.YELLOW
            3 -> Color.WHITE
            else -> Color.GREEN
        }
    }

    /**
     * This method display on a preview view the founds detections
     *
     * @param[objs] A list of detected objects
     * @param[adjustFunc] A function for mirroring the result is currently used camera is front camera
     */
    private fun manageAnalyzedObjs(objs : List<DetectedObject>, adjustFunc : (BoundingBox) -> BoundingBox) {
        val analyzeView = findViewById<ConstraintLayout>(R.id.analyze_view).apply { removeAllViews() }
        val textLabelView = findViewById<LinearLayout>(R.id.text_label_view).apply { removeAllViews() }

        var count = 0
        objs.forEach { obj ->

            Log.d("Detected_OBJ", obj.stringObjs())

            if(count++ < 5) {

                val width = analyzeView.width
                val height = analyzeView.height

                val bitmap = Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)

                val canvas = Canvas(bitmap)
                val boundingBox = adjustFunc(obj.boundingBox)

                val rectF = Rect((boundingBox.left.coerceAtMost(1.0f) * canvas.width).toInt(),
                    (boundingBox.top.coerceAtMost(1.0f) * canvas.height).toInt(),
                    (boundingBox.right.coerceAtMost(1.0f) * canvas.width).toInt(),
                    (boundingBox.bottom.coerceAtMost(1.0f) * canvas.height).toInt())

                canvas.drawRect(rectF,Paint().apply {
                    color = this@EyeTrackingActivity.getAnalyzeColor(count)
                    strokeWidth = 10f
                    style = Paint.Style.STROKE
                })

                val imageView = ImageView(this@EyeTrackingActivity).apply {
                    setImageBitmap(bitmap)
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }

                val logVal = StringBuilder("[")

                for(ll in obj.labels) {
                    logVal.append("${ll.label} ${ll.score}")
                }

                logVal.append("]\n")

                analyzeView.addView(imageView)
                val textView = TextView(this@EyeTrackingActivity).apply {
                    text = logVal.toString()
                    setTextColor(this@EyeTrackingActivity.getAnalyzeColor(count))
                    setTypeface(null, Typeface.BOLD)
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )

                }
                textLabelView.addView(textView)
            }
        }
    }

    private fun DetectedObject.stringObjs() : String {
        return "id[${this.trackingId}] labels[${this.labels.map { "${it.label},${it.displayName},${it.score}" }}] ${this.boundingBox}"
    }
}