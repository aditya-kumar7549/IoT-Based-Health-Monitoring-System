package com.example.healthmonitoringapp;

import android.content.Intent;
import android.content.SharedPreferences; // ADDED FOR SAVING PHONE
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

public class SettingsFragment extends AppCompatActivity {

    private EditText etName, etAge, etDetails, etEmergencyPhone; // ADDED etEmergencyPhone
    private RadioGroup rgGender;
    private Button btnSave;
    private ImageButton btnBack;
    private DatabaseHelper dbHelper;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.fragment_settings);

        dbHelper = new DatabaseHelper(this);

        // Initialize Views
        etName = findViewById(R.id.etName);
        etAge = findViewById(R.id.etAge);
        etDetails = findViewById(R.id.etDetails);
        etEmergencyPhone = findViewById(R.id.etEmergencyPhone); // INITIALIZE
        rgGender = findViewById(R.id.rgGender);
        btnSave = findViewById(R.id.btnSave);
        btnBack = findViewById(R.id.btnBack);

        // NEW: Load existing emergency number if it was saved previously
        SharedPreferences prefs = getSharedPreferences("AppPrefs", MODE_PRIVATE);
        etEmergencyPhone.setText(prefs.getString("EmergencyPhone", ""));

        // Back button logic
        btnBack.setOnClickListener(v -> {
            if (dbHelper.hasData()) {
                finish();
            } else {
                Toast.makeText(this, "Please save your profile first", Toast.LENGTH_SHORT).show();
            }
        });

        btnSave.setOnClickListener(v -> {
            String name = etName.getText().toString().trim();
            String age = etAge.getText().toString().trim();
            String details = etDetails.getText().toString().trim();
            String phone = etEmergencyPhone.getText().toString().trim(); // GET PHONE TEXT

            int selectedId = rgGender.getCheckedRadioButtonId();
            String gender = "Not Specified";

            if (selectedId != -1) {
                RadioButton rb = findViewById(selectedId);
                gender = rb.getText().toString();
            }

            if (age.isEmpty() || phone.isEmpty()) { // ENSURE PHONE ISN'T EMPTY
                Toast.makeText(this, "Please fill Age and Emergency Phone!", Toast.LENGTH_SHORT).show();
            } else {
                // 1. Save general data to SQLite
                dbHelper.saveUser(name, gender, age, details);

                // 2. Save Phone Number to SharedPreferences
                SharedPreferences.Editor editor = prefs.edit();
                editor.putString("EmergencyPhone", phone);
                editor.apply();

                Toast.makeText(this, "Data Saved Successfully", Toast.LENGTH_SHORT).show();

                // 3. Restart MainActivity
                Intent intent = new Intent(SettingsFragment.this, MainActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);

                // 4. Close this screen
                finish();
            }
        });
    }
}