package com.mypro.ocr;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.databinding.DataBindingUtil;

import com.mypro.ocr.databinding.ActivityMainBinding;

public class MainActivity extends AppCompatActivity {
    private ActivityMainBinding binding;
    ActivityResultLauncher<Intent> resultScanLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() == Activity.RESULT_OK){
                            Intent intent = result.getData();
                            String text = intent.getStringExtra(ScanActivity.EXTRA_TEXT);
                            binding.tvResult.setText(text);
                        }
                    });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = DataBindingUtil.setContentView(this, R.layout.activity_main);
        initView();
    }

    private void initView() {
        binding.btnScan.setOnClickListener(view -> {
            resultScanLauncher.launch(new Intent(this, ScanActivity.class));
        });
    }
}