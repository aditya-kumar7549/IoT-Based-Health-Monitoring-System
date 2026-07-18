package com.example.healthmonitoringapp;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import org.tensorflow.lite.Interpreter;
import java.io.FileInputStream;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import android.content.res.AssetFileDescriptor;
import java.io.IOException;

public class MainActivity extends AppCompatActivity {

    // ================= UI =================
    TextView txtDate, txtTime, txtStatus;
    TextView txtHeartRate, txtSpo2, txtTemp, txtFirebaseStatus;
    TextView txtDeviceStatus;
    TextView txtOperationMode;
    MaterialButton btnOn, btnOff;
    ImageButton btnSettings;
    TextView txtDisplayName, txtDisplayAgeGender, txtDisplayDetails;

    // ================= DATABASE & TIME =================
    private DatabaseHelper dbHelper;
    private Handler timeHandler;
    private Runnable timeRunnable;

    // ================= FIREBASE =================
    DatabaseReference healthRef;
    DatabaseReference connectedRef;

    // ================= HEARTBEAT, ML, AUDIO =================
    private Long previousLastSeen = null;
    private long lastChangeTime = 0;
    private static final long DEVICE_TIMEOUT = 5000; // 5 sec
    private Interpreter tflite;

    private int stableReadingCount = 0;
    private int lastPredictedClass = -1;
    private ToneGenerator toneGenerator;

    private Thread beepThread = null;
    private volatile boolean keepBeeping = false;

    // ================= ALERTS & NOTIFICATIONS =================
    private String emergencyPhoneNumber = "";
    private static final String CHANNEL_ID = "HighRiskAlerts";
    private static final int NOTIFICATION_PERMISSION_CODE = 101;

    // ================= COUNTDOWN TIMER =================
    private Handler countdownHandler = new Handler(Looper.getMainLooper());
    private Runnable countdownRunnable;
    private boolean isCountdownActive = false;
    private int countdownSeconds = 10;

    // ================= NATIVE BPM MATH =================
    private float smoothedIR = 0;
    private float lastSmoothedIR = 0;
    private long lastBeatTime = 0;
    private static final int RATE_SIZE = 15;
    private long[] rates = new long[RATE_SIZE];
    private byte rateSpot = 0;
    private int beatAvg = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initUI();
        dbHelper = new DatabaseHelper(this);

        if (!dbHelper.hasData()) {
            Intent intent = new Intent(MainActivity.this, SettingsFragment.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            finish();
            return;
        }

        SharedPreferences prefs = getSharedPreferences("AppPrefs", MODE_PRIVATE);
        emergencyPhoneNumber = prefs.getString("EmergencyPhone", "");

        createNotificationChannel();
        requestNotificationPermission();

        startDateTimeUpdater();

        toneGenerator = new ToneGenerator(AudioManager.STREAM_ALARM, 100);

        try {
            tflite = new Interpreter(loadModelFile());
        } catch (IOException e) {
            e.printStackTrace();
            android.util.Log.e("ML_ERROR", "Error loading model: " + e.getMessage());
        }

        healthRef = FirebaseDatabase.getInstance().getReference("health_monitoring/data");
        connectedRef = FirebaseDatabase.getInstance().getReference(".info/connected");

        btnSettings.setOnClickListener(v -> startActivity(new Intent(MainActivity.this, SettingsFragment.class)));

        connectedRef.addValueEventListener(new ValueEventListener() {
            @SuppressLint("SetTextI18n")
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                Boolean connected = snapshot.getValue(Boolean.class);
                txtFirebaseStatus.setText(connected != null && connected ? "Firebase: Connected" : "Firebase: Disconnected");
                txtFirebaseStatus.setTextColor(connected != null && connected ? 0xFF4CAF50 : 0xFFF44336);
            }
            @Override public void onCancelled(@NonNull DatabaseError error) {}
        });

        healthRef.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (!snapshot.exists()) return;
                try {
                    String opMode = snapshot.child("operationMode").getValue(String.class);
                    String sensorStatus = snapshot.child("sensorStatus").getValue(String.class);
                    String fingerStatus = snapshot.child("fingerDetected").getValue(String.class);
                    Double temperature = snapshot.child("temp").getValue(Double.class);

                    if (opMode != null) {
                        txtOperationMode.setText("Mode: " + opMode);
                    }

                    // Check if we are in Manual Mode FIRST
                    boolean isManual = "Manual".equals(opMode) || "Manual".equals(sensorStatus);

                    // ================= SpO2 LOGIC =================
                    Integer spo2 = snapshot.child("spo2").getValue(Integer.class);
                    if (spo2 != null) {
                        // Only add 15 if we are in Automatic mode
                        if (!isManual) {
                            spo2 = spo2 + 15;
                            // Prevent it from ever showing 112%
                            if (spo2 > 100) {
                                spo2 = 100;
                            }
                        }
                    }
                    // ==============================================

                    // Pull both raw IR and pre-calculated HeartRate from Firebase
                    Integer firebaseHeartRate = snapshot.child("heartRate").getValue(Integer.class);
                    Long irValueLong = snapshot.child("irValue").getValue(Long.class);
                    long irValue = (irValueLong != null) ? irValueLong : 0;
                    Integer heartRate = null;

                    if (isManual) {
                        // 1. MANUAL MODE: Bypass Math and Read BPM Directly
                        heartRate = firebaseHeartRate;

                        // Reset native math variables so it doesn't get confused when you switch back
                        beatAvg = 0;
                        smoothedIR = 0;

                    } else {
                        // 2. AUTOMATIC MODE
                        if (firebaseHeartRate != null && firebaseHeartRate > 0) {
                            // If Firebase provided a valid BPM, use it directly!
                            heartRate = firebaseHeartRate;
                            beatAvg = 0;
                            smoothedIR = 0;
                        } else {
                            // If NO valid BPM from Firebase, calculate it natively using raw IR
                            if (irValue > 50000) { // IR_THRESHOLD
                                if (smoothedIR == 0) smoothedIR = irValue;

                                smoothedIR = (smoothedIR * 0.8f) + (irValue * 0.2f);
                                float delta = smoothedIR - lastSmoothedIR;
                                lastSmoothedIR = smoothedIR;

                                long currentTime = System.currentTimeMillis();

                                if (delta > 150 && (currentTime - lastBeatTime) > 350) {
                                    long ibi = currentTime - lastBeatTime;
                                    lastBeatTime = currentTime;

                                    float currentBpm = 60000f / ibi;

                                    if (currentBpm > 40 && currentBpm < 180) {
                                        rates[rateSpot++] = (long) currentBpm;
                                        rateSpot %= RATE_SIZE;

                                        long total = 0;
                                        for (int i = 0; i < RATE_SIZE; i++) {
                                            total += rates[i];
                                        }
                                        beatAvg = (int) (total / RATE_SIZE);
                                    }
                                }

                                if (beatAvg > 0) {
                                    heartRate = beatAvg;
                                }
                            } else {
                                beatAvg = 0;
                                smoothedIR = 0;
                            }
                        }
                    }

                    // ==========================================================

                    if ("Not Detected".equals(fingerStatus)) {
                        txtHeartRate.setText("❤️ Heart Rate\nPlace Finger");
                        txtHeartRate.setTextColor(0xFFFACC15);
                        txtSpo2.setText("🩸 SpO₂\nPlace Finger");
                        txtSpo2.setTextColor(0xFFFACC15);
                    } else {
                        if (heartRate != null && heartRate > 0) {
                            txtHeartRate.setText("❤️ Heart Rate\n" + heartRate + " BPM");
                            txtHeartRate.setTextColor(0xFFFFFFFF);
                        } else {
                            txtHeartRate.setText("❤️ Heart Rate\nCalculating...");
                            txtHeartRate.setTextColor(0xFFFACC15);
                        }
                        if (spo2 != null) {
                            txtSpo2.setText("🩸 SpO₂\n" + spo2 + " %");
                            txtSpo2.setTextColor(0xFFFFFFFF);
                        }
                    }

                    if (temperature != null) {
                        double tempC = temperature;
                        double tempF = (tempC * 9 / 5) + 32;
                        String valStr = String.format(Locale.getDefault(), "%.1f °C / %.1f °F", tempC, tempF);
                        long diff = System.currentTimeMillis() - lastChangeTime;
                        boolean isOffline = (lastChangeTime != 0 && diff >= DEVICE_TIMEOUT);

                        if (isOffline || "Disconnected".equals(sensorStatus)) {
                            txtTemp.setText("🌡 Temperature: Not Connected\n Last Value: " + valStr);
                            txtTemp.setTextColor(0xFFF44336);
                        } else {
                            txtTemp.setText("🌡 Temperature\n" + valStr);
                            txtTemp.setTextColor(0xFFFFFFFF);
                        }
                    }

                    if (heartRate != null && spo2 != null && temperature != null && "Detected".equals(fingerStatus)) {
                        if (heartRate > 30 && spo2 > 85) {
                            stableReadingCount++;
                            if (stableReadingCount >= 5) {
                                classifyHealth(heartRate, spo2, temperature);
                            } else {
                                txtStatus.setText("Status: Stabilizing Data... (" + stableReadingCount + "/5)");
                                txtStatus.setTextColor(0xFFFACC15);
                            }
                        } else {
                            txtStatus.setText("Status: Reading Sensor...");
                            txtStatus.setTextColor(0xFFFACC15);
                        }
                    } else if (!"Detected".equals(fingerStatus)) {
                        stableReadingCount = 0;
                        lastPredictedClass = -1;
                        stopBeeping();
                        cancelSosCountdown();
                        txtStatus.setText("Status: Waiting for Data");
                        txtStatus.setTextColor(0xFFFACC15);
                    }

                    Object value = snapshot.child("lastSeen").getValue();
                    if (value != null) {
                        long newLastSeen = (value instanceof Long) ? (Long) value : ((Integer) value).longValue();
                        if (previousLastSeen == null || newLastSeen != previousLastSeen) {
                            previousLastSeen = newLastSeen;
                            lastChangeTime = System.currentTimeMillis();
                        }
                    }
                } catch (Exception e) { e.printStackTrace(); }
            }
            @Override public void onCancelled(@NonNull DatabaseError error) {}
        });

        setupLEDButtons();
    }

    private void classifyHealth(int heartRate, int spo2, double temp) {
        if (spo2 < 90) {
            updateStatusUI(0);
            return;
        }

        if (tflite == null) return;

        try {
            float meanHR = 116.0994749f;
            float scaleHR = 43.0677029f;
            float meanTemp = 37.8542720f;
            float scaleTemp = 1.7874321f;
            float meanSpo2 = 95.1118296f;
            float scaleSpo2 = 2.9679503f;

            float[][] input = new float[1][3];
            input[0][0] = ((float) heartRate - meanHR) / scaleHR;
            input[0][1] = ((float) temp - meanTemp) / scaleTemp;
            input[0][2] = ((float) spo2 - meanSpo2) / scaleSpo2;

            float[][] output = new float[1][3];
            tflite.run(input, output);

            int predictedClass = 0;
            float maxProb = -1.0f;
            for (int i = 0; i < 3; i++) {
                if (output[0][i] > maxProb) {
                    maxProb = output[0][i];
                    predictedClass = i;
                }
            }

            updateStatusUI(predictedClass);

        } catch (Exception e) {
            android.util.Log.e("ML_ERROR", "Inference error: " + e.getMessage());
        }
    }

    private void updateStatusUI(int predictedClass) {
        final String statusText;
        final int statusColor;

        if (predictedClass != 0) {
            cancelSosCountdown();
        }

        switch (predictedClass) {
            case 0:
                statusText = "Status: High Risk";
                statusColor = 0xFFF44336;
                break;
            case 1:
                statusText = "Status: Low Risk";
                statusColor = 0xFFFF9800;
                break;
            case 2:
                statusText = "Status: Normal";
                statusColor = 0xFF4CAF50;
                break;
            default:
                statusText = "Status: Analyzing...";
                statusColor = 0xFFFFFFFF;
                break;
        }

        if (predictedClass != lastPredictedClass) {
            if (predictedClass == 0) {
                startSosCountdown();
            }
            lastPredictedClass = predictedClass;
            playAlertSound(predictedClass);
        }

        if (!isCountdownActive) {
            runOnUiThread(() -> {
                txtStatus.setText(statusText);
                txtStatus.setTextColor(statusColor);
            });
        }
    }

    private void startSosCountdown() {
        if (isCountdownActive) return;

        isCountdownActive = true;
        countdownSeconds = 10;

        countdownRunnable = new Runnable() {
            @Override
            public void run() {
                if (countdownSeconds > 0) {
                    runOnUiThread(() -> {
                        txtStatus.setText("High Risk: SOS in " + countdownSeconds + "s");
                        txtStatus.setTextColor(0xFFF44336);
                    });
                    countdownSeconds--;
                    countdownHandler.postDelayed(this, 1000);
                } else {
                    isCountdownActive = false;
                    runOnUiThread(() -> txtStatus.setText("Status: High Risk (SOS Sent!)"));

                    sendSOSAlert();
                    showHighRiskNotification();
                }
            }
        };
        countdownHandler.post(countdownRunnable);
    }

    private void cancelSosCountdown() {
        if (isCountdownActive) {
            countdownHandler.removeCallbacks(countdownRunnable);
            isCountdownActive = false;
            runOnUiThread(() -> Toast.makeText(MainActivity.this, "Vitals improved. SOS Cancelled.", Toast.LENGTH_SHORT).show());
        }
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_PERMISSION_CODE);
            }
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            CharSequence name = "Emergency Alerts";
            String description = "Alerts the user when High Risk health conditions are detected";
            int importance = NotificationManager.IMPORTANCE_HIGH;
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, name, importance);
            channel.setDescription(description);

            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(channel);
            }
        }
    }

    private void showHighRiskNotification() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_heart)
                .setContentTitle("🚨 HIGH RISK DETECTED 🚨")
                .setContentText("Patient vitals have dropped to a critical level. Please check app!")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true);

        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (notificationManager != null) {
            notificationManager.notify(1, builder.build());
        }
    }

    private void sendSOSAlert() {
        if (emergencyPhoneNumber == null || emergencyPhoneNumber.isEmpty()) {
            runOnUiThread(() -> Toast.makeText(MainActivity.this, "SOS Failed: No Phone Number Set!", Toast.LENGTH_LONG).show());
            return;
        }

        String apiKey = "ucQ3J8heFbMET9XHria1AIyCGnosL2SkYOpvRPzldW5NmDBj7tV94Bj7N0MyDAUnZ5uiL2IrS6hfbYWd";

        String tempPhone = emergencyPhoneNumber.replaceAll("[^0-9]", "");
        if (tempPhone.length() > 10) {
            tempPhone = tempPhone.substring(tempPhone.length() - 10);
        }

        final String finalPhone = tempPhone;

        new Thread(() -> {
            try {
                String rawMessage = "EMERGENCY: Health App detected HIGH RISK for " +
                        txtDisplayName.getText().toString().replace("Name: ", "") + ". " +
                        " Please check immediately!";

                String encodedMessage = java.net.URLEncoder.encode(rawMessage, "UTF-8");

                String urlString = "https://www.fast2sms.com/dev/bulkV2?authorization=" + apiKey +
                        "&message=" + encodedMessage +
                        "&language=english&route=q&numbers=" + finalPhone;

                java.net.URL url = new java.net.URL(urlString);
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("authorization", apiKey);

                int responseCode = conn.getResponseCode();

                if (responseCode == 200) {
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, "Fast2SMS Alert Sent Successfully!", Toast.LENGTH_LONG).show());
                } else {
                    java.io.InputStream stream = conn.getErrorStream();
                    if (stream == null) stream = conn.getInputStream();
                    java.util.Scanner s = new java.util.Scanner(stream).useDelimiter("\\A");
                    String errorTxt = s.hasNext() ? s.next() : "Unknown Error";

                    runOnUiThread(() -> Toast.makeText(MainActivity.this, "Fast2SMS Error: " + errorTxt, Toast.LENGTH_LONG).show());
                }
            } catch (Exception e) {
                e.printStackTrace();
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Internet Error: Could not send SMS.", Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private void stopBeeping() {
        keepBeeping = false;
        if (beepThread != null) {
            beepThread.interrupt();
            beepThread = null;
        }
        if (toneGenerator != null) {
            toneGenerator.stopTone();
        }
    }

    private void playAlertSound(int status) {
        stopBeeping();
        keepBeeping = true;

        beepThread = new Thread(() -> {
            try {
                if (status == 2) {
                    playBeeps(1);
                } else if (status == 1) {
                    playBeeps(2);
                } else if (status == 0) {
                    while (keepBeeping) {
                        playBeeps(1);
                        Thread.sleep(800);
                    }
                }
            } catch (InterruptedException e) {
            }
        });
        beepThread.start();
    }

    private void playBeeps(int count) throws InterruptedException {
        for (int i = 0; i < count; i++) {
            if (!keepBeeping) break;
            if (toneGenerator != null) {
                toneGenerator.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 500);
            }
            Thread.sleep(800);
        }
    }

    private MappedByteBuffer loadModelFile() throws IOException {
        AssetFileDescriptor fileDescriptor = this.getAssets().openFd("health_monitoring_model.tflite");
        FileInputStream inputStream = new FileInputStream(fileDescriptor.getFileDescriptor());
        FileChannel fileChannel = inputStream.getChannel();
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, fileDescriptor.getStartOffset(), fileDescriptor.getDeclaredLength());
    }

    private void setupLEDButtons() {
        int colorActive = 0xFF6200EE;
        int colorDisabled = 0xFF9E9E9E;

        btnOn.setOnClickListener(v -> {
            healthRef.child("ledStatus").setValue("ON");
            btnOn.setEnabled(false); btnOn.setBackgroundColor(colorDisabled);
            btnOff.setEnabled(true); btnOff.setBackgroundColor(colorActive);
        });

        btnOff.setOnClickListener(v -> {
            healthRef.child("ledStatus").setValue("OFF");
            btnOff.setEnabled(false); btnOff.setBackgroundColor(colorDisabled);
            btnOn.setEnabled(true); btnOn.setBackgroundColor(colorActive);
        });
    }

    @Override protected void onResume() { super.onResume(); loadSavedProfile(); }

    private void loadSavedProfile() {
        Cursor cursor = dbHelper.getUserData();
        if (cursor != null && cursor.moveToFirst()) {
            @SuppressLint("Range") String name = cursor.getString(cursor.getColumnIndex("NAME"));
            @SuppressLint("Range") String gender = cursor.getString(cursor.getColumnIndex("GENDER"));
            @SuppressLint("Range") String age = cursor.getString(cursor.getColumnIndex("AGE"));
            @SuppressLint("Range") String details = cursor.getString(cursor.getColumnIndex("DETAILS"));
            txtDisplayName.setText("Name: " + name);
            txtDisplayAgeGender.setText("Age: " + age + " | " + gender);
            txtDisplayDetails.setText("Note: " + details);
            cursor.close();
        }

        SharedPreferences prefs = getSharedPreferences("AppPrefs", MODE_PRIVATE);
        emergencyPhoneNumber = prefs.getString("EmergencyPhone", "");
    }

    private void initUI() {
        txtDate = findViewById(R.id.txtDate);
        txtTime = findViewById(R.id.txtTime);
        txtStatus = findViewById(R.id.txtHeartResult);
        txtHeartRate = findViewById(R.id.txtHeartRate);
        txtSpo2 = findViewById(R.id.txtSpo2);
        txtTemp = findViewById(R.id.txtTemperature);
        txtFirebaseStatus = findViewById(R.id.txtFirebaseStatus);
        txtDeviceStatus = findViewById(R.id.txtDeviceStatus);
        btnOn = findViewById(R.id.btnOn);
        btnOff = findViewById(R.id.btnOff);
        btnSettings = findViewById(R.id.btnSettings);
        txtDisplayName = findViewById(R.id.txtDisplayName);
        txtDisplayAgeGender = findViewById(R.id.txtDisplayAgeGender);
        txtDisplayDetails = findViewById(R.id.txtDisplayDetails);
        txtOperationMode = findViewById(R.id.txtOperationMode);

        txtOperationMode.setText("Mode: Automatic");
    }

    private void startDateTimeUpdater() {
        timeHandler = new Handler(Looper.getMainLooper());
        timeRunnable = () -> { updateDateTime(); timeHandler.postDelayed(timeRunnable, 1000); };
        timeHandler.post(timeRunnable);
    }

    @SuppressLint("SetTextI18n")
    private void updateDateTime() {
        txtDate.setText("📅 Date\n" + new SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(new Date()));
        txtTime.setText("⏰ Time\n" + new SimpleDateFormat("hh:mm:ss a", Locale.getDefault()).format(new Date()));

        if (lastChangeTime != 0) {
            long diff = System.currentTimeMillis() - lastChangeTime;
            if (diff < DEVICE_TIMEOUT) {
                txtDeviceStatus.setText("🟢 ONLINE (ESP)");
                txtDeviceStatus.setTextColor(0xFF4CAF50);
            } else {
                txtDeviceStatus.setText("🔴 OFFLINE (ESP)");
                txtDeviceStatus.setTextColor(0xFFF44336);

                txtOperationMode.setText("Mode: Offline");
                txtStatus.setText("Status: Device Offline");

                String currentTemp = txtTemp.getText().toString();
                if (!currentTemp.contains("Not Connected") && !currentTemp.contains("No Data")) {
                    String lastVal = currentTemp.substring(currentTemp.indexOf("\n") + 1);
                    txtTemp.setText("🌡 Temperature: Not Connected\n Last Value: " + lastVal);
                    txtTemp.setTextColor(0xFFF44336);
                }
            }
        } else {
            txtOperationMode.setText("Mode: Offline");
        }
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (timeHandler != null) timeHandler.removeCallbacks(timeRunnable);
        if (countdownHandler != null) countdownHandler.removeCallbacks(countdownRunnable);
        stopBeeping();
        if (toneGenerator != null) {
            toneGenerator.release();
        }
    }
}