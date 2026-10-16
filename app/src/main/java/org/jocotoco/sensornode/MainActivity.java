package org.jocotoco.sensornode;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private TextView info;
    private static final int REQ_PERMISSIONS = 101;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(24, 26, 24, 30);
        scroll.addView(layout);
        TextView title = new TextView(this);
        title.setText("JOCOTOCO · SensorNode 0.1.0");
        title.setTextSize(23);
        title.setTextColor(Color.rgb(22, 82, 62));
        layout.addView(title);
        info = new TextView(this);
        info.setTextSize(16);
        info.setPadding(0, 22, 0, 22);
        layout.addView(info);

        addButton(layout, "1 · Solicitar permisos", new View.OnClickListener() {
            @Override public void onClick(View view) { requestMissingPermissions(); }
        });
        addButton(layout, "2 · Iniciar servidor local", new View.OnClickListener() {
            @Override public void onClick(View view) { startNode(); }
        });
        addButton(layout, "Copiar token (autenticación)", new View.OnClickListener() {
            @Override public void onClick(View view) {
                ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (clipboard != null) {
                    clipboard.setPrimaryClip(ClipData.newPlainText("SensorNode token", Secrets.token(MainActivity.this)));
                    Toast.makeText(MainActivity.this, "Token copiado", Toast.LENGTH_SHORT).show();
                }
            }
        });
        addButton(layout, "Consultar estado", new View.OnClickListener() {
            @Override public void onClick(View view) { updateInfo(); }
        });
        addButton(layout, "Detener servidor", new View.OnClickListener() {
            @Override public void onClick(View view) { stopService(new Intent(MainActivity.this, SensorNodeService.class)); updateInfo(); }
        });
        setContentView(scroll);
        updateInfo();
    }

    private void addButton(LinearLayout layout, String label, View.OnClickListener onClick) {
        Button button = new Button(this);
        button.setText(label);
        button.setOnClickListener(onClick);
        layout.addView(button);
    }

    private void requestMissingPermissions() {
        if (Build.VERSION.SDK_INT >= 23) {
            requestPermissions(new String[] { Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO }, REQ_PERMISSIONS);
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        updateInfo();
    }

    private void startNode() {
        Intent intent = new Intent(this, SensorNodeService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent);
        else startService(intent);
        updateInfo();
        Toast.makeText(this, "Inicio solicitado. Consulte /v1/health", Toast.LENGTH_LONG).show();
    }

    private boolean hasPermission(String perm) { return checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED; }
    private void updateInfo() {
        info.setText("Modelo: " + Build.MODEL + "\nAndroid: " + Build.VERSION.RELEASE
            + "\nCámara autorizada: " + hasPermission(Manifest.permission.CAMERA)
            + "\nMicrófono autorizado: " + hasPermission(Manifest.permission.RECORD_AUDIO)
            + "\n\nPuerto HTTP: 127.0.0.1:8765 (solo teléfono)"
            + "\nToken de este teléfono:\n" + Secrets.token(this)
            + "\n\nPara acceder desde Windows utiliza SSH -L."
            + "\nNo expongas el puerto HTTP a Internet."
            + "\n\nEl token es secreto; no publiques capturas de esta pantalla.");
    }
}
