package uz.gita.cameragallery


import android.Manifest
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Chronometer
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCapture.FLASH_MODE_OFF
import androidx.camera.core.ImageCapture.FLASH_MODE_ON
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.core.content.ContextCompat
import by.kirich1409.viewbindingdelegate.viewBinding
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.common.util.concurrent.ListenableFuture
import com.karumi.dexter.Dexter
import com.karumi.dexter.MultiplePermissionsReport
import com.karumi.dexter.PermissionToken
import com.karumi.dexter.listener.PermissionRequest
import com.karumi.dexter.listener.multi.MultiplePermissionsListener
import kotlinx.coroutines.*
import uz.gita.cameragallery.databinding.ActivityMainBinding


class MainActivity : AppCompatActivity(R.layout.activity_main) {
    private val binding by viewBinding(ActivityMainBinding::bind, R.id.container)

    private var flashMode: Int = 0
    private var isTorchOn = false
    private var isRecord = false
    private var isResume = false
    private lateinit var recording: Recording
    private var isFront = false
    private lateinit var preview: Preview
    private lateinit var cameraSelector: CameraSelector
    private lateinit var cameraManager: CameraManager
    private lateinit var cameraProcessFuture: ListenableFuture<ProcessCameraProvider>
    private var imageCapture: ImageCapture? = null

    @SuppressLint("RestrictedApi")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        supportActionBar?.hide()

        Dexter.withContext(this)
            .withPermissions(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ).withListener(object : MultiplePermissionsListener {
                override fun onPermissionsChecked(report: MultiplePermissionsReport) {
                    report.let {
                        if (report.areAllPermissionsGranted()) {
                            cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                            initPreviews()
                        }
                    }
                }

                override fun onPermissionRationaleShouldBeShown(
                    permissions: List<PermissionRequest>,
                    token: PermissionToken
                ) {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    intent.data = Uri.parse("package:$packageName")
                    startActivity(intent)
                }
            }).check()

        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager

        val selector = QualitySelector.from(
            Quality.FHD,
            FallbackStrategy.higherQualityOrLowerThan(Quality.SD)
        )

        val recorder = Recorder.Builder()
            .setQualitySelector(selector)
            .build()

        val videoCapture = VideoCapture.withOutput(recorder)

        binding.takePhoto.setOnClickListener {
            capture()
            binding.ivRedIcon.visibility = View.GONE
        }

        binding.ivTorch.setOnClickListener {
            if (isTorchOn) {
                isTorchOn = false
                imageCapture?.camera?.cameraControl?.enableTorch(false)
                videoCapture.camera?.cameraControl?.enableTorch(false)
                binding.ivTorch.setImageResource(R.drawable.baseline_flashlight_off_24)
            } else {
                isTorchOn = true
                imageCapture?.camera?.cameraControl?.enableTorch(true)
                videoCapture.camera?.cameraControl?.enableTorch(true)
                binding.ivTorch.setImageResource(R.drawable.baseline_flashlight_on_24)
            }

//            if (isRecord) {
//                binding.ivTorch.setImageResource(R.drawable.baseline_flashlight_off_24)
//            } else {
//                binding.ivTorch.setImageResource(R.drawable.baseline_flashlight_on_24)
//            }

        }

        binding.btnFlash.setOnClickListener {
            when (flashMode) {
                0 -> {
                    flashMode++
                    imageCapture!!.flashMode = ImageCapture.FLASH_MODE_AUTO
                    binding.btnFlash.setImageResource(R.drawable.ic_baseline_flash_auto_24)
                }
                1 -> {
                    flashMode++
                    imageCapture!!.flashMode = ImageCapture.FLASH_MODE_ON
                    binding.btnFlash.setImageResource(R.drawable.ic_baseline_flash_on_24)
                }
                2 -> {
                    flashMode = 0
                    imageCapture!!.flashMode = ImageCapture.FLASH_MODE_OFF
                    binding.btnFlash.setImageResource(R.drawable.ic_baseline_flash_off_24)
                }
            }
        }

        binding.videoCapture.setOnClickListener {
            if (isRecord) {
                isRecord = false
                recording.stop()
                initPreviews()
                binding.ivRedIcon.visibility = View.GONE
                binding.btnFlash.visibility = View.VISIBLE
                binding.takePhoto.visibility = View.VISIBLE
                binding.replace.visibility = View.VISIBLE
                binding.videoCapture.setImageResource(R.drawable.ic_baseline_circle_24)
                binding.counter.stop()
                binding.counter.base = SystemClock.elapsedRealtime()
                binding.counter.text = "00.00"
            } else {
                isRecord = true
                videoCapture(videoCapture)
                binding.ivRedIcon.visibility = View.VISIBLE
                binding.btnFlash.visibility = View.GONE
                binding.takePhoto.visibility = View.GONE
                binding.replace.visibility = View.GONE
                binding.videoCapture.setImageResource(R.drawable.ic_baseline_square_24)
                binding.ivTorch.setImageResource(R.drawable.baseline_flashlight_off_24)
                isTorchOn = false
                binding.counter.start()
            }
        }

        binding.resumeVideo.setOnClickListener {
            if (isResume) {
                recording.resume()
            } else {
                recording.pause()
            }
        }

        binding.replace.setOnClickListener { its ->
            ValueAnimator.ofFloat(-360f).apply {
                addUpdateListener {
                    its.rotation = animatedValue as Float
                }
                start()
                duration = 500

            }
            if (isFront) {
                cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                isFront = false
            } else {
                cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
                isFront = true
            }
            initPreviews()
        }
    }


    private fun initPreviews() {
        preview = Preview.Builder().build()
        cameraProcessFuture = ProcessCameraProvider.getInstance(this)
        cameraProcessFuture.addListener({

            val cameraProvider = cameraProcessFuture.get()

            imageCapture = ImageCapture.Builder().build()

            preview.setSurfaceProvider(binding.preView.surfaceProvider)

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview,
                    imageCapture
                )
            } catch (_: Exception) {
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun capture() {
        preview = Preview.Builder().build()
        cameraProcessFuture = ProcessCameraProvider.getInstance(this)
        val time = System.currentTimeMillis()
        val contentValue = ContentValues()

        contentValue.put(MediaStore.MediaColumns.DISPLAY_NAME, time)
        contentValue.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")

        val imageCapture = imageCapture ?: return
        val outputOptions = ImageCapture.OutputFileOptions.Builder(
            contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValue
        ).build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    Toast.makeText(
                        this@MainActivity,
                        "Image saved successfully",
                        Toast.LENGTH_SHORT
                    ).show()
                }

                override fun onError(exception: ImageCaptureException) {
                    Toast.makeText(
                        this@MainActivity,
                        "Image not saved successfully",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        )
    }

    @SuppressLint("MissingPermission", "RestrictedApi")
    fun videoCapture(videoCapture: VideoCapture<Recorder>) {
        preview = Preview.Builder().build()
        preview.setSurfaceProvider(binding.preView.surfaceProvider)
        cameraProcessFuture = ProcessCameraProvider.getInstance(this)
        if (isTorchOn) videoCapture.camera?.cameraControl?.enableTorch(true)
        cameraProcessFuture.addListener({

            val cameraProvider = cameraProcessFuture.get()

            val time = System.currentTimeMillis()
            val contentValue = ContentValues()
            contentValue.put(MediaStore.MediaColumns.DISPLAY_NAME, time)
            contentValue.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")

            val mediaStoreOutput = MediaStoreOutputOptions.Builder(
                contentResolver,
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            )
                .setContentValues(contentValue)
                .build()

            recording = videoCapture.output
                .prepareRecording(this, mediaStoreOutput)
                .withAudioEnabled()
                .start(ContextCompat.getMainExecutor(this)) {

                }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    videoCapture,
                    preview
                )
            } catch (_: Exception) {
            }

        }, ContextCompat.getMainExecutor(this))
    }
}


