package org.las2mile.scrcpy;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.Toast;

public class SettingsActivity extends Activity {
    private static final String PREFERENCE_KEY = "default";
    private static final String PREFERENCE_SPINNER_RESOLUTION = "spinner_resolution";
    private static final String PREFERENCE_SPINNER_BITRATE = "spinner_bitrate";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        final EditText editTextServerHost = findViewById(R.id.editText_server_host);
        final Switch switchNav = findViewById(R.id.switch1);
        final Switch switchAmlogic = findViewById(R.id.switch2);
        final Button startButton = findViewById(R.id.button_start);

        // Restore preferences
        editTextServerHost.setText(getSharedPreferences(PREFERENCE_KEY, 0).getString("Server Address", ""));
        switchNav.setChecked(getSharedPreferences(PREFERENCE_KEY, 0).getBoolean("Nav Switch", false));
        switchAmlogic.setChecked(getSharedPreferences(PREFERENCE_KEY, 0).getBoolean("Amlogic Mode", false));

        setSpinner(R.array.options_resolution_values, R.id.spinner_video_resolution, PREFERENCE_SPINNER_RESOLUTION);
        setSpinner(R.array.options_bitrate_keys, R.id.spinner_video_bitrate, PREFERENCE_SPINNER_BITRATE);

        startButton.setOnClickListener(v -> startPlayer());
    }

    private void startPlayer() {
        final EditText editTextServerHost = findViewById(R.id.editText_server_host);
        final Spinner videoResolutionSpinner = findViewById(R.id.spinner_video_resolution);
        final Spinner videoBitrateSpinner = findViewById(R.id.spinner_video_bitrate);
        final Switch switchNav = findViewById(R.id.switch1);
        final Switch switchAmlogic = findViewById(R.id.switch2);

        String serverAdr = editTextServerHost != null ? editTextServerHost.getText().toString().trim() : "";
        getSharedPreferences(PREFERENCE_KEY, 0).edit().putString("Server Address", serverAdr).apply();

        boolean nav = switchNav != null && switchNav.isChecked();
        boolean useAmlogicMode = switchAmlogic != null && switchAmlogic.isChecked();
        getSharedPreferences(PREFERENCE_KEY, 0).edit().putBoolean("Nav Switch", nav).apply();
        getSharedPreferences(PREFERENCE_KEY, 0).edit().putBoolean("Amlogic Mode", useAmlogicMode).apply();

        if (serverAdr.isEmpty()) {
            Toast.makeText(this, R.string.error_server_address_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!isValidIpv4(serverAdr)) {
            if (editTextServerHost != null) {
                editTextServerHost.setError(getString(R.string.error_invalid_ip));
            }
            Toast.makeText(this, R.string.error_invalid_ip, Toast.LENGTH_SHORT).show();
            return;
        }

        String[] videoResolutions = getResources().getStringArray(R.array.options_resolution_values)[videoResolutionSpinner.getSelectedItemPosition()].split("x");
        int screenWidth = Integer.parseInt(videoResolutions[0]);
        int screenHeight = Integer.parseInt(videoResolutions[1]);
        int videoBitrate = getResources().getIntArray(R.array.options_bitrate_values)[videoBitrateSpinner.getSelectedItemPosition()];

        Intent intent = new Intent(this, PlayerActivity.class);
        intent.putExtra(PlayerActivity.EXTRA_IP, serverAdr);
        intent.putExtra(PlayerActivity.EXTRA_WIDTH, screenWidth);
        intent.putExtra(PlayerActivity.EXTRA_HEIGHT, screenHeight);
        intent.putExtra(PlayerActivity.EXTRA_BITRATE, videoBitrate);
        intent.putExtra(PlayerActivity.EXTRA_NAV, nav);
        intent.putExtra(PlayerActivity.EXTRA_AMLOGIC_MODE, useAmlogicMode);
        startActivity(intent);
    }

    private void setSpinner(final int textArrayOptionResId, final int spinnerViewResId, final String preferenceId) {
        final Spinner spinner = findViewById(spinnerViewResId);
        if (spinner == null) {
            return;
        }
        ArrayAdapter<CharSequence> adapter =
                ArrayAdapter.createFromResource(this, textArrayOptionResId, android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, android.view.View view, int position, long id) {
                getSharedPreferences(PREFERENCE_KEY, 0).edit().putInt(preferenceId, position).apply();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                getSharedPreferences(PREFERENCE_KEY, 0).edit().putInt(preferenceId, 0).apply();
            }
        });
        spinner.setSelection(getSharedPreferences(PREFERENCE_KEY, 0).getInt(preferenceId, 0));
    }

    private static boolean isValidIpv4(String host) {
        if (host == null) {
            return false;
        }
        String[] parts = host.split("\\.");
        if (parts.length != 4) {
            return false;
        }
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3) {
                return false;
            }
            int value;
            try {
                value = Integer.parseInt(part);
            } catch (NumberFormatException e) {
                return false;
            }
            if (value < 0 || value > 255) {
                return false;
            }
        }
        return true;
    }
}
