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
import com.termux.shared.activity.ActivityUtils;

public class OpenHouseAgreementActivity extends AppCompatActivity {

    public static final String EXTRA_OPEN_MAINTENANCE_AFTER_ACCEPT = "open_maintenance_after_accept";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_openhouse_agreement);

        TextView humanReadableView = findViewById(R.id.agreementHumanReadableContent);
        TextView formalView = findViewById(R.id.agreementFormalContent);
        CheckBox acceptCheckBox = findViewById(R.id.agreementAcceptCheckbox);
        Button acceptButton = findViewById(R.id.buttonAgreementAccept);
        Button cancelButton = findViewById(R.id.buttonAgreementCancel);

        humanReadableView.setMovementMethod(new ScrollingMovementMethod());
        formalView.setMovementMethod(new ScrollingMovementMethod());

        humanReadableView.setText(getString(R.string.agreement_human_readable_content));
        formalView.setText(getString(R.string.agreement_formal_content));

        acceptButton.setEnabled(false);
        acceptCheckBox.setOnCheckedChangeListener((buttonView, isChecked) -> acceptButton.setEnabled(isChecked));

        cancelButton.setOnClickListener(v -> finish());
        acceptButton.setOnClickListener(v -> {
            OpenHouseAgreement.acceptCurrentVersion(this);
            if (getIntent().getBooleanExtra(EXTRA_OPEN_MAINTENANCE_AFTER_ACCEPT, false)) {
                ActivityUtils.startActivity(this, new Intent(this, MaintenanceCenterActivity.class));
            }
            finish();
        });
    }
}
