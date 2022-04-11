package it.dani.cameraapp.view

import android.Manifest
import android.graphics.*
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.app.ActivityCompat
import androidx.lifecycle.LifecycleOwner
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.snackbar.Snackbar
import it.dani.cameraapp.R
import it.dani.cameraapp.camera.CameraManager
import it.dani.cameraapp.camera.EyeTrackingDetector
import it.dani.cameraapp.camera.ObjectDetection
import it.dani.cameraapp.game.Question
import it.dani.cameraapp.game.QuestionDB
import it.dani.cameraapp.game.QuestionFetcher
import it.dani.cameraapp.motion.EyeMotionDetector
import it.dani.cameraapp.view.utils.PermissionUtils
import it.dani.cameraapp.view.utils.ViewUtils
import java.util.concurrent.Executors

class GameActivity : AppCompatActivity() {

    private lateinit var questionFetcher : QuestionFetcher

    private var index = 0
    private lateinit var questionDB : QuestionDB
    private lateinit var buttons : List<ExtendedFloatingActionButton>
    private var beenAskedPermission = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        super.setContentView(R.layout.game_activity)
        ViewUtils.hideSystemBars(this.window)

        this.buttons = listOf(R.id.aResponse,R.id.bResponse,R.id.cResponse,R.id.dResponse).map { findViewById(it) }

        this.questionFetcher = QuestionFetcher().apply {
            onSuccess += {
                if(it.questions.isNotEmpty()) {
                    runOnUiThread {
                        findViewById<View>(R.id.waitingOverlay).apply { visibility = View.GONE }
                    }
                    this@GameActivity.index = 0
                    this@GameActivity.questionDB = QuestionDB(it.questions.filter { q -> q.incorrectAnswer.size == 3})

                    try {
                        this@GameActivity.displayGame(it,this@GameActivity.index)
                    } catch (e : Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }

        this.questionFetcher.fetch()

        if(!PermissionUtils.permissionGranted(this, arrayOf(Manifest.permission.CAMERA))) {
            if(!this.beenAskedPermission) {
                this.beenAskedPermission = true
                ActivityCompat.requestPermissions(this,arrayOf(Manifest.permission.CAMERA),0xA1)
            }
        } else {
            CameraManager.provideCamera(this,this::bindCameraCases)
        }
    }

    private fun displayGame(questions : QuestionDB, index : Int) {
        questions.questions[index].let { q ->
            runOnUiThread {
                findViewById<TextView>(R.id.questionText).apply {
                    text = q.question
                }

                this.displayAnswerButtons(q)
            }
        }
    }

    private fun displayAnswerButtons(question: Question) {
        val gameActivity = findViewById<View>(R.id.game_activity)

        val answers = mutableListOf(question.correctAnswer).also { a -> a.addAll(question.incorrectAnswer) }

        this.buttons.forEachIndexed { i,b ->
            b.apply {
                if(i < answers.size) {
                    text = answers[i].value
                    setOnClickListener {
                        val response = when(this.text) {
                            question.correctAnswer.value -> {
                                this@GameActivity.correctAnswerAction()
                                this@GameActivity.resources.getString(R.string.game_answer_correct)
                            }
                            else -> this@GameActivity.resources.getString(R.string.game_answer_wrong)
                        }
                        Snackbar.make(gameActivity,response,Snackbar.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun correctAnswerAction() {
        Executors.newCachedThreadPool().execute {
            try {
                Thread.sleep(2000)

                if(++this.index < this.questionDB.questions.size) {
                    this.displayGame(this.questionDB,this.index)

                } else {
                    runOnUiThread {
                        findViewById<View>(R.id.waitingOverlay).apply { visibility = View.VISIBLE }
                    }
                    this.questionFetcher.fetch()
                }

            } catch (e : InterruptedException) {}
        }
    }

    private fun bindCameraCases(cameraProvider : ProcessCameraProvider) {
        val cameraSelected = CameraSelector.LENS_FACING_FRONT

        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(cameraSelected)
            .build()

        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        val analyzer : ObjectDetection = EyeTrackingDetector()

        EyeMotionDetector(analyzer).apply {
            val handler : (Pair<Float,Float>,Pair<Float,Float>) -> Unit = { l,r ->
                val x = 1.0f - (l.first + r.first) / 2
                val y = (l.second + r.second) / 2


                Log.i("Game_cursor","x = $x, y = $y")

                runOnUiThread {
                    when {
                        x <= 0.30 && y <= 0.30 -> this@GameActivity.buttons[0].callOnClick()
                        x >= 0.70 && y <= 0.30 -> this@GameActivity.buttons[1].callOnClick()
                        x <= 0.30 && y >= 0.70 -> this@GameActivity.buttons[2].callOnClick()
                        x >= 0.70 && y >= 0.70 -> this@GameActivity.buttons[3].callOnClick()
                    }
                }

                val analyzeView = findViewById<ConstraintLayout>(R.id.analyze_view)
                val width = analyzeView.width
                val height = analyzeView.height

                val bitmap = Bitmap.createBitmap(width,height, Bitmap.Config.ARGB_8888)

                val canvas = Canvas(bitmap)

                canvas.drawCircle(x * width - DOT_RADIUS,y * height - DOT_RADIUS, DOT_RADIUS, Paint().apply {
                    color = Color.RED
                })

                val imageView = ImageView(this@GameActivity).apply {
                    setImageBitmap(bitmap)
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }

                runOnUiThread {
                    analyzeView.removeAllViews()
                    analyzeView.addView(imageView)
                }
            }

            onEyeLeft += handler
            onEyeRight += handler
            onEyeUp += handler
            onEyeDown += handler
        }

        analysis.setAnalyzer(Executors.newSingleThreadExecutor(),analyzer)

        cameraProvider.unbindAll()
        cameraProvider.bindToLifecycle(this as LifecycleOwner,cameraSelector,analysis)
    }

    companion object {
        private const val DOT_RADIUS = 100f
    }
}