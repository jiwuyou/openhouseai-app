package com.termux.app.activities;

import android.os.Bundle;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.termux.R;

import java.io.File;
import java.io.IOException;

public class MaintenanceLogActivity extends AppCompatActivity {

    public static final String EXTRA_STAGE_SLUG = "stage_slug";
    public static final String EXTRA_STAGE_LABEL = "stage_label";

    private TextView titleView;
    private TextView pathView;
    private TextView bodyView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_maintenance_log);

        titleView = findViewById(R.id.fullLogTitle);
        pathView = findViewById(R.id.fullLogPath);
        bodyView = findViewById(R.id.fullLogBody);

        findViewById(R.id.buttonRefreshLog).setOnClickListener(v -> renderLog());
    }

    @Override
    protected void onResume() {
        super.onResume();
        renderLog();
    }

    private void renderLog() {
        String stageSlug = getIntent().getStringExtra(EXTRA_STAGE_SLUG);
        String stageLabel = getIntent().getStringExtra(EXTRA_STAGE_LABEL);

        if (stageLabel == null || stageLabel.isEmpty()) {
            titleView.setText(R.string.full_log_title);
        } else {
            titleView.setText(getString(R.string.full_log_title_with_stage, stageLabel));
        }

        File file = MaintainerLogStore.getLogFile(stageSlug);
        pathView.setText(getString(R.string.full_log_path, file.getAbsolutePath()));

        try {
            String content = MaintainerLogStore.readLog(stageSlug);
            bodyView.setText(content.isEmpty() ? getString(R.string.full_log_empty) : content);
        } catch (IOException e) {
            bodyView.setText(getString(R.string.full_log_error, e.getMessage()));
        }
    }
}
