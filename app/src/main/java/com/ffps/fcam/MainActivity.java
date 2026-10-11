package com.ffps.fcam;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.hardware.Camera;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    private EditText eThr, eGap, eCnt, eInt, ePort, ePass;
    private CheckBox cbOn, cbAuto;
    private Spinner spCam;
    private TextView tvStatus, tvHint;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        eThr = (EditText) findViewById(R.id.eThr);
        eGap = (EditText) findViewById(R.id.eGap);
        eCnt = (EditText) findViewById(R.id.eCnt);
        eInt = (EditText) findViewById(R.id.eInt);
        ePort = (EditText) findViewById(R.id.ePort);
        ePass = (EditText) findViewById(R.id.ePass);
        cbOn = (CheckBox) findViewById(R.id.cbOn);
        cbAuto = (CheckBox) findViewById(R.id.cbAuto);
        spCam = (Spinner) findViewById(R.id.spCam);
        tvStatus = (TextView) findViewById(R.id.tvStatus);
        tvHint = (TextView) findViewById(R.id.tvHint);

        String ver = "";
        try {
            ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        ((TextView) findViewById(R.id.tvVer)).setText("fCam " + ver);

        int n = 0;
        try {
            n = Camera.getNumberOfCameras();
        } catch (Exception ignored) {
        }
        String[] names = new String[Math.max(n, 1)];
        if (n == 0) names[0] = "Камер не найдено";
        for (int i = 0; i < n; i++) {
            String kind = "";
            try {
                Camera.CameraInfo ci = new Camera.CameraInfo();
                Camera.getCameraInfo(i, ci);
                kind = ci.facing == Camera.CameraInfo.CAMERA_FACING_FRONT ? " (фронтальная)" : " (задняя)";
            } catch (Exception ignored) {
            }
            names[i] = "Камера " + i + kind;
        }
        ArrayAdapter<String> ad = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, names);
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spCam.setAdapter(ad);

        Prefs p = Prefs.load(this);
        cbOn.setChecked(p.enabled);
        cbAuto.setChecked(p.autostart);
        if (p.camera < names.length) spCam.setSelection(p.camera);
        eThr.setText(fmt(p.threshold));
        eGap.setText(String.valueOf(p.minGap));
        eCnt.setText(String.valueOf(p.count));
        eInt.setText(fmt(p.interval));
        ePort.setText(String.valueOf(p.port));
        ePass.setText(p.password);

        tvHint.setText("Команда: http://адрес:порт/api/shot?password=пароль\nФото: DCIM/fCam/");

        findViewById(R.id.btnSave).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                save();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        tvStatus.setText(CamService.running ? "Состояние: работает" : "Состояние: остановлено");
    }

    private static String fmt(float f) {
        if (f == (int) f) return String.valueOf((int) f);
        return String.valueOf(f);
    }

    private void save() {
        Prefs p = new Prefs();
        try {
            p.enabled = cbOn.isChecked();
            p.autostart = cbAuto.isChecked();
            p.camera = Math.max(0, spCam.getSelectedItemPosition());
            p.threshold = Float.parseFloat(eThr.getText().toString().trim().replace(',', '.'));
            p.minGap = Integer.parseInt(eGap.getText().toString().trim());
            p.count = Integer.parseInt(eCnt.getText().toString().trim());
            p.interval = Float.parseFloat(eInt.getText().toString().trim().replace(',', '.'));
            p.port = Integer.parseInt(ePort.getText().toString().trim());
            p.password = ePass.getText().toString();
        } catch (NumberFormatException e) {
            Toast.makeText(this, "Проверьте числовые поля", Toast.LENGTH_LONG).show();
            return;
        }
        if (p.threshold <= 0 || p.minGap < 0 || p.count < 1 || p.interval < 0 || p.port < 1 || p.port > 65535) {
            Toast.makeText(this, "Некорректное значение в настройках", Toast.LENGTH_LONG).show();
            return;
        }
        p.save(this);
        if (p.enabled && Build.VERSION.SDK_INT >= 23
                && (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)) {
            requestPermissions(new String[]{Manifest.permission.CAMERA,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
            return;
        }
        apply();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        boolean cam = false;
        for (int i = 0; i < perms.length; i++) {
            if (Manifest.permission.CAMERA.equals(perms[i]) && res[i] == PackageManager.PERMISSION_GRANTED) cam = true;
        }
        if (cam) {
            apply();
        } else {
            Toast.makeText(this, "Нужно разрешение на камеру", Toast.LENGTH_LONG).show();
        }
    }

    private void apply() {
        CamService.stop(this);
        Prefs p = Prefs.load(this);
        if (p.enabled) CamService.start(this);
        tvStatus.setText(p.enabled ? "Состояние: работает" : "Состояние: остановлено");
        Toast.makeText(this, "Сохранено", Toast.LENGTH_SHORT).show();
    }
}
