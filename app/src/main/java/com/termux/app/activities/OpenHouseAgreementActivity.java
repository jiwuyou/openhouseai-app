package com.termux.app.activities;

import android.content.Intent;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.termux.R;
import com.termux.app.OpenHouseAgreement;
import com.termux.app.TermuxActivity;
import com.termux.shared.activity.ActivityUtils;

public class OpenHouseAgreementActivity extends AppCompatActivity {

    public static final String EXTRA_OPEN_MAINTENANCE_AFTER_ACCEPT = "open_maintenance_after_accept";
    public static final String EXTRA_OPEN_MENU_AFTER_ACCEPT = "open_menu_after_accept";
    public static final String EXTRA_OPEN_INSTALL_GUIDE_AFTER_ACCEPT = "open_install_guide_after_accept";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_openhouse_agreement);

        TextView humanReadableView = findViewById(R.id.agreementHumanReadableContent);
        TextView formalView = findViewById(R.id.agreementFormalContent);
        CheckBox acceptCheckBoxTop = findViewById(R.id.agreementAcceptCheckboxTop);
        CheckBox acceptCheckBox = findViewById(R.id.agreementAcceptCheckbox);
        Button acceptButtonTop = findViewById(R.id.buttonAgreementAcceptTop);
        Button acceptButton = findViewById(R.id.buttonAgreementAccept);
        Button cancelButtonTop = findViewById(R.id.buttonAgreementCancelTop);
        Button cancelButton = findViewById(R.id.buttonAgreementCancel);

        humanReadableView.setMovementMethod(new ScrollingMovementMethod());
        formalView.setMovementMethod(new ScrollingMovementMethod());

        humanReadableView.setText(getString(R.string.agreement_human_readable_content));
        formalView.setText(getString(R.string.agreement_formal_content));

        acceptButtonTop.setEnabled(false);
        acceptButton.setEnabled(false);
        acceptCheckBoxTop.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (acceptCheckBox.isChecked() != isChecked) {
                acceptCheckBox.setChecked(isChecked);
            }
            acceptButtonTop.setEnabled(isChecked);
            acceptButton.setEnabled(isChecked);
        });
        acceptCheckBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (acceptCheckBoxTop.isChecked() != isChecked) {
                acceptCheckBoxTop.setChecked(isChecked);
            }
            acceptButtonTop.setEnabled(isChecked);
            acceptButton.setEnabled(isChecked);
        });

        cancelButtonTop.setOnClickListener(v -> returnToTerminal());
        cancelButton.setOnClickListener(v -> returnToTerminal());
        acceptButtonTop.setOnClickListener(v -> acceptAgreementAndContinue());
        acceptButton.setOnClickListener(v -> acceptAgreementAndContinue());
    }

    @Override
    public void onBackPressed() {
        returnToTerminal();
    }

    private void returnToTerminal() {
        finish();
    }

    private void acceptAgreementAndContinue() {
        OpenHouseAgreement.acceptCurrentVersion(this);
        if (getIntent().getBooleanExtra(EXTRA_OPEN_MAINTENANCE_AFTER_ACCEPT, false)) {
            ActivityUtils.startActivity(this, new Intent(this, MaintenanceCenterActivity.class));
        } else if (getIntent().getBooleanExtra(EXTRA_OPEN_MENU_AFTER_ACCEPT, false)) {
            Intent intent = new Intent(this, TermuxActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            intent.putExtra(TermuxActivity.EXTRA_OPENHOUSE_MENU_AFTER_AGREEMENT, true);
            ActivityUtils.startActivity(this, intent);
        } else if (getIntent().getBooleanExtra(EXTRA_OPEN_INSTALL_GUIDE_AFTER_ACCEPT, false)) {
            ActivityUtils.startActivity(this, new Intent(this, OpenHouseOnboardingActivity.class));
        } else {
            Intent intent = new Intent(this, TermuxActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            ActivityUtils.startActivity(this, intent);
        }
        finish();
    }
}
