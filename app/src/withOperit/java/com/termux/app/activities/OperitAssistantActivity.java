package com.termux.app.activities;

import android.os.Bundle;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.termux.R;
import com.termux.app.operit.core.OperitAssistantFacade;
import com.termux.app.operit.core.OperitAssistantResponse;
import com.termux.app.operit.runtime.OperitCommandResult;
import com.termux.app.operit.runtime.OperitRuntimeBridge;
import com.termux.app.operit.runtime.OperitServiceManagerResult;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class OperitAssistantActivity extends AppCompatActivity {

    public static final String EXTRA_HOSTED_MODE = "com.termux.app.operit.EXTRA_HOSTED_MODE";
    public static final String EXTRA_HELP_MODE = "com.termux.app.operit.EXTRA_HELP_MODE";

    private final ExecutorService backgroundExecutor = Executors.newSingleThreadExecutor();

    private OperitAssistantFacade assistantFacade;
    private ScrollView outputScrollView;
    private TextView titleView;
    private TextView outputView;
    private TextView statusView;
    private EditText inputView;
    private Button submitButton;
    private boolean hostedMode;
    private boolean helpMode;
    private boolean commandRunning;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_operit_assistant);

        hostedMode = getIntent() != null && getIntent().getBooleanExtra(EXTRA_HOSTED_MODE, false);
        helpMode = getIntent() != null && getIntent().getBooleanExtra(EXTRA_HELP_MODE, false);

        assistantFacade = new OperitAssistantFacade(
            getApplicationContext(),
            new OperitRuntimeBridge(getApplicationContext())
        );
        outputScrollView = findViewById(R.id.operitAssistantOutputScroll);
        titleView = findViewById(R.id.operitAssistantTitle);
        outputView = findViewById(R.id.operitAssistantOutput);
        statusView = findViewById(R.id.operitAssistantStatus);
        inputView = findViewById(R.id.operitAssistantInput);
        submitButton = findViewById(R.id.operitAssistantSubmit);

        findViewById(R.id.operitAssistantBack).setOnClickListener(v -> finish());
        submitButton.setOnClickListener(v -> submitCurrentInput());
        inputView.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                submitCurrentInput();
                return true;
            }
            return false;
        });

        bindQuickCommand(R.id.operitQuickTermux, "/termux pwd && uname -a");
        bindQuickCommand(R.id.operitQuickUbuntu, "/ubuntu pwd && uname -a");
        bindQuickCommand(R.id.operitQuickServiceHealth, "/service-manager health");
        bindQuickCommand(R.id.operitQuickServiceStatus, "/service-manager status smallphone-core");

        applyHostedPresentation();
        setStatus(getString(R.string.operit_assistant_status_ready));
    }

    @Override
    public void onBackPressed() {
        finish();
    }

    @Override
    protected void onDestroy() {
        backgroundExecutor.shutdownNow();
        super.onDestroy();
    }

    private void applyHostedPresentation() {
        if (isHelpPresentation()) {
            titleView.setText(R.string.operit_page_ai_friend_help_title);
            setTitle(R.string.operit_page_ai_friend_help_title);
            outputView.setText(getString(R.string.operit_page_ai_friend_help_welcome));
            return;
        }

        titleView.setText(R.string.operit_page_assistant_title);
        setTitle(R.string.operit_page_assistant_title);
        outputView.setText(getString(R.string.operit_page_assistant_welcome));
    }

    private boolean isHelpPresentation() {
        return hostedMode || helpMode;
    }

    private void bindQuickCommand(int buttonId, String command) {
        View view = findViewById(buttonId);
        if (view instanceof Button) {
            view.setOnClickListener(v -> {
                inputView.setText(command);
                inputView.setSelection(command.length());
                submitCommand(command);
            });
        }
    }

    private void submitCurrentInput() {
        String command = inputView.getText() == null ? "" : inputView.getText().toString().trim();
        if (command.isEmpty()) {
            Toast.makeText(this, R.string.operit_assistant_empty_input, Toast.LENGTH_SHORT).show();
            return;
        }
        submitCommand(command);
    }

    private void submitCommand(String command) {
        if (commandRunning) {
            Toast.makeText(this, R.string.operit_assistant_busy, Toast.LENGTH_SHORT).show();
            return;
        }

        commandRunning = true;
        setControlsEnabled(false);
        setStatus(getString(R.string.operit_assistant_status_running));
        appendOutput("\n\n> " + command + "\n");

        backgroundExecutor.execute(() -> {
            try {
                OperitAssistantResponse response = assistantFacade.submit(command);
                runOnUiThread(() -> {
                    appendOutput(formatResponse(response));
                    finishCommand(response != null && response.isSuccess()
                        ? getString(R.string.operit_assistant_status_ready)
                        : getString(R.string.operit_assistant_status_failed));
                });
            } catch (Throwable throwable) {
                runOnUiThread(() -> {
                    String message = throwable.getMessage();
                    appendOutput(getString(R.string.operit_assistant_error_prefix)
                        + (isBlank(message) ? throwable.getClass().getSimpleName() : message));
                    finishCommand(getString(R.string.operit_assistant_status_failed));
                });
            }
        });
    }

    private void finishCommand(String status) {
        commandRunning = false;
        setControlsEnabled(true);
        setStatus(status);
    }

    private void setControlsEnabled(boolean enabled) {
        submitButton.setEnabled(enabled);
        findViewById(R.id.operitQuickTermux).setEnabled(enabled);
        findViewById(R.id.operitQuickUbuntu).setEnabled(enabled);
        findViewById(R.id.operitQuickServiceHealth).setEnabled(enabled);
        findViewById(R.id.operitQuickServiceStatus).setEnabled(enabled);
    }

    private void setStatus(String status) {
        statusView.setText(status);
    }

    private void appendOutput(String text) {
        outputView.append(text);
        outputScrollView.post(() -> outputScrollView.fullScroll(View.FOCUS_DOWN));
    }

    private String formatResponse(OperitAssistantResponse response) {
        if (response == null) {
            return getString(R.string.operit_assistant_empty_result);
        }

        StringBuilder builder = new StringBuilder();
        if (!isBlank(response.getOutput())) {
            builder.append(response.getOutput());
        }

        if (response.hasServiceManagerResult()) {
            appendServiceManagerResult(builder, response.getServiceManagerResult());
        } else if (response.hasCommandResult()) {
            OperitCommandResult commandResult = response.getCommandResult();
            appendSection(builder, "exitCode", String.valueOf(commandResult.exitCode));
            appendSection(builder, "durationMs", String.valueOf(commandResult.durationMs));
            if (commandResult.timedOut) {
                appendSection(builder, "timedOut", "true");
            }
            appendSection(builder, "stdout", commandResult.stdout);
            appendSection(builder, "stderr", commandResult.stderr);
            appendSection(builder, "error", commandResult.error);
        } else if (!response.isSuccess()) {
            appendSection(builder, "error", response.getError());
        }

        if (builder.length() == 0) {
            return getString(R.string.operit_assistant_empty_result);
        }
        return builder.toString();
    }

    private void appendServiceManagerResult(StringBuilder builder, OperitServiceManagerResult result) {
        if (result == null) {
            return;
        }
        appendSection(builder, "code", String.valueOf(result.code));
        appendSection(builder, "url", result.url);
        appendSection(builder, "message", result.message);
        appendSection(builder, "serviceId", result.serviceId);
        appendSection(builder, "state", result.state);
        appendSection(builder, "provider", result.provider);
        if (result.pid >= 0) {
            appendSection(builder, "pid", String.valueOf(result.pid));
        }
        appendSection(builder, "serviceUrl", result.serviceUrl);
        appendSection(builder, "body", result.body);
        appendSection(builder, "error", result.error);
        appendSection(builder, "durationMs", String.valueOf(result.durationMs));
    }

    private void appendSection(StringBuilder builder, String label, String value) {
        if (isBlank(value)) {
            return;
        }
        if (builder.length() > 0) {
            builder.append('\n');
        }
        builder.append(label).append(": ").append(value);
    }

    private boolean isBlank(String text) {
        return text == null || text.trim().isEmpty();
    }
}
