package com.pocketvpnbridge;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.method.HideReturnsTransformationMethod;
import android.text.method.PasswordTransformationMethod;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.security.SecureRandom;

public class MainActivity extends Activity {
    private static final int REQUEST_LOCAL_NETWORK = 4101;
    private static final int REQUEST_NOTIFICATIONS = 4102;
    private static final String PREFS = "bridge_settings";
    private static final String KEY_PORT = "port";
    private static final String KEY_USERNAME = "username";
    private static final String KEY_PASSWORD = "password";
    private static final String KEY_KEEP_AWAKE = "keep_awake";
    private static final String KEY_RUNNING = "service_running";
    private static final String LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK";
    private static final char[] PASSWORD_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789".toCharArray();

    private EditText portInput;
    private EditText usernameInput;
    private EditText passwordInput;
    private CheckBox wakeLockCheck;
    private TextView statusText;
    private TextView addressText;
    private TextView networkHint;
    private TextView startButton;
    private TextView stopButton;
    private TextView generatePasswordButton;
    private TextView togglePasswordButton;
    private TextView copyPasswordButton;
    private boolean passwordVisible;
    private String currentAddress;
    private boolean pendingStart;
    private boolean receiverRegistered;

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!ProxyService.ACTION_STATE.equals(intent.getAction())) return;
            boolean running = intent.getBooleanExtra(ProxyService.EXTRA_RUNNING, false);
            String message = intent.getStringExtra(ProxyService.EXTRA_MESSAGE);
            int connections = intent.getIntExtra(ProxyService.EXTRA_CONNECTIONS, 0);
            updateUi(running, message, connections);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        bindViews();
        loadSettings();
        wireActions();
        refreshUi();
    }

    private void bindViews() {
        portInput = findViewById(R.id.portInput);
        usernameInput = findViewById(R.id.usernameInput);
        passwordInput = findViewById(R.id.passwordInput);
        wakeLockCheck = findViewById(R.id.wakeLockCheck);
        statusText = findViewById(R.id.statusText);
        addressText = findViewById(R.id.addressText);
        networkHint = findViewById(R.id.networkHint);
        startButton = findViewById(R.id.startButton);
        stopButton = findViewById(R.id.stopButton);
        generatePasswordButton = findViewById(R.id.generatePasswordButton);
        togglePasswordButton = findViewById(R.id.togglePasswordButton);
        copyPasswordButton = findViewById(R.id.copyPasswordButton);
    }

    private void loadSettings() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        portInput.setText(String.valueOf(prefs.getInt(KEY_PORT, 1080)));
        usernameInput.setText(prefs.getString(KEY_USERNAME, "android"));
        String password = prefs.getString(KEY_PASSWORD, null);
        if (password == null || password.isEmpty()) {
            password = generatePassword();
            prefs.edit().putString(KEY_PASSWORD, password).apply();
        }
        passwordInput.setText(password);
        passwordInput.setTransformationMethod(PasswordTransformationMethod.getInstance());
        passwordVisible = false;
        togglePasswordButton.setText("SHOW");
        wakeLockCheck.setChecked(prefs.getBoolean(KEY_KEEP_AWAKE, false));
    }

    private void wireActions() {
        startButton.setOnClickListener(v -> beginStart());
        stopButton.setOnClickListener(v -> stopProxy());
        generatePasswordButton.setOnClickListener(v -> {
            if (isRunning()) return;
            String password = generatePassword();
            passwordInput.setText(password);
            Toast.makeText(this, "New password generated", Toast.LENGTH_SHORT).show();
        });
        findViewById(R.id.copyAddressButton).setOnClickListener(v -> copyAddress());
        findViewById(R.id.copyConfigButton).setOnClickListener(v -> copyCurlCommand());
        togglePasswordButton.setOnClickListener(v -> togglePasswordVisibility());
        copyPasswordButton.setOnClickListener(v -> copyToClipboard("SOCKS5 password", passwordInput.getText().toString()));
        portInput.setFilters(new InputFilter[] {new InputFilter.LengthFilter(5)});
        portInput.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (!isRunning()) updateUi(false, "Connect your laptop to the same Wi-Fi network", 0);
            }
            @Override public void afterTextChanged(android.text.Editable s) {}
        });
    }

    private void beginStart() {
        int port;
        try {
            port = Integer.parseInt(portInput.getText().toString().trim());
        } catch (NumberFormatException error) {
            port = -1;
        }
        String username = usernameInput.getText().toString().trim();
        String password = passwordInput.getText().toString();
        if (port < 1024 || port > 65535) {
            toast("Choose a port between 1024 and 65535");
            return;
        }
        if (username.isEmpty() || password.isEmpty()) {
            toast("Username and password are required");
            return;
        }
        if (username.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 255
                || password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 255) {
            toast("Username and password must be at most 255 UTF-8 bytes");
            return;
        }

        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putInt(KEY_PORT, port)
                .putString(KEY_USERNAME, username)
                .putString(KEY_PASSWORD, password)
                .putBoolean(KEY_KEEP_AWAKE, wakeLockCheck.isChecked())
                .apply();

        pendingStart = true;
        if (Build.VERSION.SDK_INT >= 37
                && checkSelfPermission(LOCAL_NETWORK_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {LOCAL_NETWORK_PERMISSION}, REQUEST_LOCAL_NETWORK);
            return;
        }
        requestNotificationPermissionOrStart();
    }

    private void requestNotificationPermissionOrStart() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
            return;
        }
        launchProxyService();
    }

    private void launchProxyService() {
        pendingStart = false;
        int port = Integer.parseInt(portInput.getText().toString().trim());
        Intent service = new Intent(this, ProxyService.class).setAction(ProxyService.ACTION_START);
        service.putExtra(ProxyService.EXTRA_PORT, port);
        service.putExtra(ProxyService.EXTRA_USERNAME, usernameInput.getText().toString().trim());
        service.putExtra(ProxyService.EXTRA_PASSWORD, passwordInput.getText().toString());
        service.putExtra(ProxyService.EXTRA_KEEP_AWAKE, wakeLockCheck.isChecked());
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(service);
            else startService(service);
            updateUi(true, "Starting proxy…", 0);
        } catch (RuntimeException error) {
            updateUi(false, "Could not start proxy", 0);
            toast("Android could not start the proxy service");
        }
    }

    private void stopProxy() {
        pendingStart = false;
        stopService(new Intent(this, ProxyService.class));
        updateUi(false, "Stopping proxy…", 0);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_LOCAL_NETWORK) {
            boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (granted && pendingStart) requestNotificationPermissionOrStart();
            else {
                pendingStart = false;
                toast("Local network permission is required to accept laptop connections");
            }
        } else if (requestCode == REQUEST_NOTIFICATIONS) {
            // Notification permission is optional; a foreground service can still run.
            if (pendingStart) launchProxyService();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(ProxyService.ACTION_STATE);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(stateReceiver, filter);
        }
        receiverRegistered = true;
        refreshUi();
    }

    @Override
    protected void onStop() {
        if (receiverRegistered) {
            try { unregisterReceiver(stateReceiver); } catch (IllegalArgumentException ignored) {}
            receiverRegistered = false;
        }
        super.onStop();
    }

    private void refreshUi() {
        boolean running = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_RUNNING, false);
        updateUi(running, running ? "Proxy is running" : "Proxy stopped", 0);
    }

    private void updateUi(boolean running, String message, int connections) {
        String ip = LanAddress.currentIpv4();
        int port = 1080;
        try { port = Integer.parseInt(portInput.getText().toString().trim()); }
        catch (RuntimeException ignored) {
            port = getSharedPreferences(PREFS, MODE_PRIVATE).getInt(KEY_PORT, 1080);
        }
        currentAddress = ip == null ? null : ip + ":" + port;
        addressText.setText(currentAddress == null ? "Wi-Fi IP unavailable" : currentAddress);
        if (running) {
            statusText.setBackgroundResource(R.drawable.bg_status);
            statusText.setTextColor(getColor(R.color.mint));
            statusText.setText("● RUNNING");
            networkHint.setText(connections + " active connection" + (connections == 1 ? "" : "s")
                    + " · " + (message == null ? "Proxy active" : message));
            startButton.setAlpha(0.45f);
            stopButton.setAlpha(1f);
            startButton.setEnabled(false);
            stopButton.setEnabled(true);
            setFieldsEnabled(false);
        } else {
            statusText.setBackgroundResource(R.drawable.bg_status_off);
            statusText.setTextColor(getColor(R.color.muted));
            statusText.setText("● STOPPED");
            networkHint.setText(message == null ? "Connect your laptop to the same Wi-Fi network" : message);
            startButton.setAlpha(1f);
            stopButton.setAlpha(0.45f);
            startButton.setEnabled(true);
            stopButton.setEnabled(false);
            setFieldsEnabled(true);
        }
    }

    private void setFieldsEnabled(boolean enabled) {
        portInput.setEnabled(enabled);
        usernameInput.setEnabled(enabled);
        passwordInput.setEnabled(enabled);
        wakeLockCheck.setEnabled(enabled);
        generatePasswordButton.setEnabled(enabled);
        togglePasswordButton.setEnabled(true);
        copyPasswordButton.setEnabled(true);
        generatePasswordButton.setAlpha(enabled ? 1f : 0.4f);
    }

    private boolean isRunning() {
        return getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_RUNNING, false);
    }

    private void togglePasswordVisibility() {
        int selection = passwordInput.getSelectionStart();
        if (passwordVisible) {
            passwordInput.setTransformationMethod(PasswordTransformationMethod.getInstance());
            togglePasswordButton.setText("SHOW");
            passwordVisible = false;
        } else {
            passwordInput.setTransformationMethod(HideReturnsTransformationMethod.getInstance());
            togglePasswordButton.setText("HIDE");
            passwordVisible = true;
        }
        passwordInput.setSelection(Math.max(0, Math.min(selection, passwordInput.length())));
    }

    private void copyAddress() {
        if (currentAddress == null) {
            toast("Connect to Wi-Fi to get your phone's LAN address");
            return;
        }
        copyToClipboard("SOCKS5 address", currentAddress);
    }

    private void copyCurlCommand() {
        if (currentAddress == null) {
            toast("Connect to Wi-Fi to get your phone's LAN address");
            return;
        }
        String username = usernameInput.getText().toString().trim();
        String password = passwordInput.getText().toString();
        String[] address = currentAddress.split(":");
        String host = address[0];
        String port = address.length > 1 ? address[1] : "1080";
        String credentials = shellQuote(username + ":" + password);
        String command = "curl --proxy socks5h://" + host + ":" + port
                + " --proxy-user " + credentials + " https://api.ipify.org";
        copyToClipboard("SOCKS5 curl command", command);
    }

    private void copyToClipboard(String label, String value) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText(label, value));
        toast("Copied to clipboard");
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static String generatePassword() {
        SecureRandom random = new SecureRandom();
        StringBuilder result = new StringBuilder(22);
        for (int i = 0; i < 22; i++) result.append(PASSWORD_CHARS[random.nextInt(PASSWORD_CHARS.length)]);
        return result.toString();
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }
}
