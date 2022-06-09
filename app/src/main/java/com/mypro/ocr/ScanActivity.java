package com.mypro.ocr;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.media.Image;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.databinding.DataBindingUtil;
import androidx.lifecycle.LifecycleOwner;

import com.canhub.cropper.CropImageContract;
import com.canhub.cropper.CropImageContractOptions;
import com.canhub.cropper.CropImageOptions;
import com.canhub.cropper.CropImageView;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import com.mypro.ocr.databinding.ActivityScanBinding;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ScanActivity extends AppCompatActivity {

    public static String EXTRA_TEXT = "extra_text";
    private ActivityScanBinding binding;
    private final String[] REQUIRED_PERMISSIONS = {Manifest.permission.CAMERA};
    private final int REQUEST_CODE_PERMISSIONS = 10;
    private ImageCapture imageCapture;
    private ExecutorService cameraExecutor;
    private ImageProxy imageProxy;
    private InputImage capturedImage;
    private TextRecognizer recognizer;
    private final String FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS";
    private LoadingDialog loading;

    ActivityResultLauncher<CropImageContractOptions> cropImage =
            registerForActivityResult(new CropImageContract(),
                    result -> {
                        if (result.isSuccessful()) {
                            Uri uriContent = result.getUriContent();

                            try {
                                InputImage image = InputImage.fromFilePath(this, uriContent);
                                recognizer.process(image).addOnSuccessListener(visionText -> {
                                    loading.dismiss();

                                    visionText.getText().replaceAll("[^A-Za-z]", "");
                                    Intent intent = new Intent();
                                    intent.putExtra(EXTRA_TEXT, visionText.getText());
                                    setResult(Activity.RESULT_OK, intent);
                                    finish();
                                }).addOnFailureListener(
                                        e -> {
                                            loading.dismiss();
                                            Toast.makeText(
                                                    this,
                                                    "Failed to scan image",
                                                    Toast.LENGTH_SHORT
                                            ).show();
                                        }).addOnCompleteListener(task -> {
                                    imageProxy.close();
                                });

                                loading.show();
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        }
                    });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = DataBindingUtil.setContentView(this, R.layout.activity_scan);
        loading = new LoadingDialog(this);

        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            REQUIRED_PERMISSIONS[1] = Manifest.permission.WRITE_EXTERNAL_STORAGE;
        }

        requestPermission();
        initView();
    }

    private void initView() {
        binding.imageCaptureButton.setOnClickListener(view -> takePhoto());
        cameraExecutor = Executors.newSingleThreadExecutor();
        recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture =
                ProcessCameraProvider.getInstance(this);

        try {
            cameraProviderFuture.addListener(() -> {
                ProcessCameraProvider cameraProvider = null;
                try {
                    cameraProvider = cameraProviderFuture.get();
                } catch (ExecutionException e) {
                    e.printStackTrace();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(binding.viewFinder.getSurfaceProvider());

                imageCapture = new ImageCapture.Builder().build();

                ImageAnalysis imageAnalyzer = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();
                imageAnalyzer.setAnalyzer(cameraExecutor, new ImageAnalyzer());

                CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle((LifecycleOwner) this, cameraSelector, preview, imageCapture, imageAnalyzer);

            }, ContextCompat.getMainExecutor(this));
        } catch (Exception e) {
            Log.e("ERR", "Use case binding failed", e);
        }
    }

    private void takePhoto() {
        if (imageCapture == null)
            return;
        String name = new SimpleDateFormat(FILENAME_FORMAT, Locale.US).format(System.currentTimeMillis());

        ContentValues contentValues = new ContentValues();
        contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        contentValues.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
            contentValues.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CameraX-Image");
        }

        ImageCapture.OutputFileOptions outputOptions = new ImageCapture.OutputFileOptions.Builder
                (getContentResolver(), MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        contentValues).build();

        imageCapture.takePicture(outputOptions, ContextCompat.getMainExecutor(this), new ImageCapture.OnImageSavedCallback() {
            @Override
            public void onImageSaved(@androidx.annotation.NonNull ImageCapture.OutputFileResults outputFileResults) {
                startCrop(outputFileResults.getSavedUri());
            }

            @Override
            public void onError(@androidx.annotation.NonNull ImageCaptureException exception) {
                Log.e("ERR", "Photo capture failed: ${exc.message}", exception);
            }
        });
    }

    class ImageAnalyzer implements ImageAnalysis.Analyzer {
        @Override
        @SuppressLint("UnsafeOptInUsageError")
        public void analyze(@androidx.annotation.NonNull ImageProxy image) {
            imageProxy = image;
            Image mediaImage = image.getImage();
            if (mediaImage != null) {
                capturedImage = InputImage.fromMediaImage(mediaImage, image.getImageInfo().getRotationDegrees());
            }
        }
    }

    private void startCrop(Uri uri) {
        // start cropping activity for pre-acquired image saved on the device and customize settings
        CropImageContractOptions option = new CropImageContractOptions(uri, new CropImageOptions());
        option.setGuidelines(CropImageView.Guidelines.ON);
        option.setOutputCompressFormat(Bitmap.CompressFormat.PNG);
        cropImage.launch(option);
    }


    private void requestPermission() {

        if (allPermissionsGranted()) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(
                    this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
            );
        }
    }

    private boolean allPermissionsGranted() {
        int count = 0;
        for (String permission : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
                count++;
            }
        }
        return count == REQUIRED_PERMISSIONS.length;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @androidx.annotation.NonNull String[] permissions, @androidx.annotation.NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera();
            } else {
                Toast.makeText(
                        this,
                        "Permissions not granted by the user.",
                        Toast.LENGTH_SHORT
                ).show();
                finish();
            }
        }
    }
}