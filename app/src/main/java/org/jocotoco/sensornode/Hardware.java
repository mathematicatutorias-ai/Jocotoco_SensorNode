package org.jocotoco.sensornode;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.graphics.BitmapFactory;
import android.hardware.Camera;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.os.StatFs;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@SuppressWarnings("deprecation")
final class Hardware {
    private Hardware() {}
    private static final Object CAMERA_LOCK = new Object();
    private static final Object AUDIO_LOCK = new Object();

    static File folder(Context context) throws IOException {
        File root = new File(context.getFilesDir(), "captures");
        if (!root.exists() && !root.mkdirs()) throw new IOException("No se puede crear directorio de capturas");
        return root;
    }

    static JSONObject fileInfo(File file) throws Exception {
        JSONObject j = new JSONObject();
        j.put("name", file.getName());
        j.put("bytes", file.length());
        j.put("modified_ms", file.lastModified());
        j.put("download", "/v1/file?name=" + file.getName());
        if (file.getName().endsWith(".jpg")) {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
            j.put("width", opts.outWidth);
            j.put("height", opts.outHeight);
        } else if (file.getName().endsWith(".wav") && file.length() >= 44) {
            j.put("audio_sample_rate_hz", 16000);
            j.put("audio_channels", 1);
            j.put("audio_bits_per_sample", 16);
            j.put("duration_seconds", (file.length() - 44) / 32000.0);
        }
        return j;
    }

    static JSONObject status(Context context) throws Exception {
        JSONObject j = new JSONObject();
        j.put("app", "Jocotoco SensorNode");
        j.put("version", "0.1.0");
        j.put("manufacturer", Build.MANUFACTURER);
        j.put("model", Build.MODEL);
        j.put("android", Build.VERSION.RELEASE);
        j.put("sdk", Build.VERSION.SDK_INT);
        j.put("architecture", Build.CPU_ABI);
        j.put("camera_permission", allowed(context, Manifest.permission.CAMERA));
        j.put("microphone_permission", allowed(context, Manifest.permission.RECORD_AUDIO));
        j.put("api_bind", "127.0.0.1:8765");
        Intent bat = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (bat != null) {
            JSONObject b = new JSONObject();
            int level = bat.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = bat.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            b.put("percent", level < 0 || scale <= 0 ? JSONObject.NULL : (100.0 * level / scale));
            b.put("charging", bat.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0);
            int tenth = bat.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1);
            b.put("temperature_c", tenth < 0 ? JSONObject.NULL : tenth / 10.0);
            j.put("battery", b);
        }
        StatFs disk = new StatFs(context.getFilesDir().getAbsolutePath());
        j.put("storage_free_bytes", disk.getAvailableBytes());
        Runtime rt = Runtime.getRuntime();
        j.put("java_heap_free_bytes", rt.freeMemory());
        JSONArray cams = new JSONArray();
        try {
            for (int n = 0; n < Camera.getNumberOfCameras(); n++) {
                Camera.CameraInfo info = new Camera.CameraInfo();
                Camera.getCameraInfo(n, info);
                JSONObject camera = new JSONObject();
                camera.put("id", n);
                camera.put("facing", info.facing == Camera.CameraInfo.CAMERA_FACING_BACK ? "back" : "front");
                camera.put("sensor_orientation_degrees", info.orientation);
                cams.put(camera);
            }
        } catch (Exception e) { j.put("camera_list_error", e.toString()); }
        j.put("cameras", cams);
        j.put("file_count", folder(context).listFiles() == null ? 0 : folder(context).listFiles().length);
        return j;
    }

    static JSONObject sensors(Context context) throws Exception {
        JSONObject result = new JSONObject();
        SensorManager manager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        JSONArray available = new JSONArray();
        final Map<Integer, float[]> values = new ConcurrentHashMap<>();
        List<Sensor> sensors = manager.getSensorList(Sensor.TYPE_ALL);
        for (Sensor s : sensors) {
            JSONObject item = new JSONObject();
            item.put("type", s.getType());
            item.put("name", s.getName());
            item.put("vendor", s.getVendor());
            available.put(item);
        }
        result.put("available", available);
        final SensorEventListener listener = new SensorEventListener() {
            @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}
            @Override public void onSensorChanged(SensorEvent event) {
                values.put(event.sensor.getType(), event.values.clone());
            }
        };
        HandlerThread thread = new HandlerThread("SensorSnapshot");
        thread.start();
        Handler handler = new Handler(thread.getLooper());
        final CountDownLatch registered = new CountDownLatch(1);
        handler.post(new Runnable() {
            @Override public void run() {
                for (Sensor s : sensors) manager.registerListener(listener, s, SensorManager.SENSOR_DELAY_NORMAL, handler);
                registered.countDown();
            }
        });
        try {
            if (!registered.await(2, TimeUnit.SECONDS)) throw new IOException("No se pudieron registrar sensores");
            Thread.sleep(900);
        } finally {
            final CountDownLatch cleared = new CountDownLatch(1);
            handler.post(new Runnable() {
                @Override public void run() { manager.unregisterListener(listener); cleared.countDown(); }
            });
            cleared.await(2, TimeUnit.SECONDS);
            thread.quitSafely();
        }
        JSONArray readings = new JSONArray();
        for (Sensor s : sensors) {
            float[] reading = values.get(s.getType());
            if (reading == null) continue;
            JSONObject sensor = new JSONObject();
            sensor.put("type", s.getType());
            sensor.put("name", s.getName());
            JSONArray components = new JSONArray();
            for (float f : reading) components.put((double) f);
            sensor.put("values", components);
            readings.put(sensor);
        }
        result.put("readings", readings);
        result.put("capture_ms", System.currentTimeMillis());
        return result;
    }

    static File photo(Context context, String side) throws Exception {
        if (!allowed(context, Manifest.permission.CAMERA)) throw new SecurityException("Falta permiso CAMERA; concederlo en la APK");
        synchronized (CAMERA_LOCK) { return photoLocked(context, side); }
    }

    private static File photoLocked(Context context, String side) throws Exception {
        int selected = -1;
        int wanted = "front".equals(side) ? Camera.CameraInfo.CAMERA_FACING_FRONT : Camera.CameraInfo.CAMERA_FACING_BACK;
        for (int i = 0; i < Camera.getNumberOfCameras(); i++) {
            Camera.CameraInfo info = new Camera.CameraInfo();
            Camera.getCameraInfo(i, info);
            if (info.facing == wanted) { selected = i; break; }
        }
        if (selected < 0) throw new IllegalArgumentException("No hay cámara " + side);
        final int cameraId = selected;
        final File target = new File(folder(context), name("photo", ".jpg"));
        final HandlerThread cameraThread = new HandlerThread("SensorNode-Camera");
        cameraThread.start();
        final Handler handler = new Handler(cameraThread.getLooper());
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final AtomicBoolean finished = new AtomicBoolean(false);
        final Camera[] camera = new Camera[1];
        final SurfaceTexture[] texture = new SurfaceTexture[1];
        PowerManager.WakeLock wake = acquireWakeLock(context, 23000);
        try {
            handler.post(new Runnable() {
                @Override public void run() {
                    try {
                        camera[0] = Camera.open(cameraId);
                        Camera.Parameters params = camera[0].getParameters();
                        Camera.Size best = null;
                        for (Camera.Size size : params.getSupportedPictureSizes()) {
                            if (best == null || size.width * (long) size.height > best.width * (long) best.height) best = size;
                        }
                        if (best != null) params.setPictureSize(best.width, best.height);
                        params.setJpegQuality(95);
                        camera[0].setParameters(params);
                        texture[0] = new SurfaceTexture(10);
                        camera[0].setPreviewTexture(texture[0]);
                        camera[0].startPreview();
                        handler.postDelayed(new Runnable() {
                            @Override public void run() {
                                if (finished.get()) return;
                                try {
                                    camera[0].takePicture(null, null, new Camera.PictureCallback() {
                                        @Override public void onPictureTaken(byte[] data, Camera source) {
                                            try (FileOutputStream stream = new FileOutputStream(target)) { stream.write(data); }
                                            catch (Exception e) { failure.set(e); target.delete(); }
                                            finally { closeCamera(camera[0], texture[0]); finished.set(true); latch.countDown(); }
                                        }
                                    });
                                } catch (Exception e) {
                                    failure.set(e); closeCamera(camera[0], texture[0]); finished.set(true); latch.countDown();
                                }
                            }
                        }, 950);
                    } catch (Exception e) {
                        failure.set(e); closeCamera(camera[0], texture[0]); finished.set(true); latch.countDown();
                    }
                }
            });
            if (!latch.await(16, TimeUnit.SECONDS)) {
                finished.set(true);
                handler.post(new Runnable() {
                    @Override public void run() { closeCamera(camera[0], texture[0]); }
                });
                throw new IOException("Tiempo de espera agotado al capturar fotografía");
            }
            if (failure.get() != null) throw new IOException("Error de cámara: " + failure.get());
            if (!target.isFile() || target.length() == 0) throw new IOException("Fotografía vacía");
            return target;
        } finally {
            if (wake != null && wake.isHeld()) wake.release();
            cameraThread.quitSafely();
        }
    }

    private static void closeCamera(Camera camera, SurfaceTexture texture) {
        try { if (camera != null) camera.stopPreview(); } catch (Exception ignored) {}
        try { if (camera != null) camera.release(); } catch (Exception ignored) {}
        try { if (texture != null) texture.release(); } catch (Exception ignored) {}
    }

    static File audio(Context context, int seconds) throws Exception {
        if (!allowed(context, Manifest.permission.RECORD_AUDIO)) throw new SecurityException("Falta permiso RECORD_AUDIO; concederlo en la APK");
        synchronized (AUDIO_LOCK) { return audioLocked(context, seconds); }
    }

    private static File audioLocked(Context context, int seconds) throws Exception {
        final int sampleRate = 16000;
        final int channels = 1;
        final int bytesPerSample = 2;
        final int min = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) throw new IOException("El dispositivo no soporta audio PCM 16 kHz mono");
        int bufferSize = Math.max(min * 2, 4096);
        File output = new File(folder(context), name("audio", ".wav"));
        AudioRecord recorder = null;
        PowerManager.WakeLock wake = acquireWakeLock(context, (seconds + 8L) * 1000L);
        try (RandomAccessFile wav = new RandomAccessFile(output, "rw")) {
            recorder = new AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize);
            if (recorder.getState() != AudioRecord.STATE_INITIALIZED) throw new IOException("No se pudo inicializar el micrófono");
            wav.setLength(0);
            wav.write(new byte[44]);
            byte[] data = new byte[bufferSize];
            long expected = (long) seconds * sampleRate * channels * bytesPerSample;
            long written = 0;
            recorder.startRecording();
            if (recorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) throw new IOException("No inició la grabación");
            while (written < expected && !Thread.currentThread().isInterrupted()) {
                int allowed = (int) Math.min(data.length, expected - written);
                int n = recorder.read(data, 0, allowed);
                if (n <= 0) throw new IOException("AudioRecord.read devolvió " + n);
                wav.write(data, 0, n);
                written += n;
            }
            if (written != expected) throw new IOException("Grabación interrumpida");
            writeWavHeader(wav, written, sampleRate, channels, bytesPerSample * 8);
            return output;
        } catch (Exception e) {
            output.delete();
            throw e;
        } finally {
            if (recorder != null) {
                try { recorder.stop(); } catch (Exception ignored) {}
                recorder.release();
            }
            if (wake != null && wake.isHeld()) wake.release();
        }
    }

    private static void writeWavHeader(RandomAccessFile out, long size, int rate, int channels, int bits) throws IOException {
        out.seek(0);
        out.writeBytes("RIFF"); writeLE(out, size + 36, 4);
        out.writeBytes("WAVEfmt "); writeLE(out, 16, 4); writeLE(out, 1, 2);
        writeLE(out, channels, 2); writeLE(out, rate, 4);
        writeLE(out, rate * channels * bits / 8, 4);
        writeLE(out, channels * bits / 8, 2); writeLE(out, bits, 2);
        out.writeBytes("data"); writeLE(out, size, 4);
    }
    private static void writeLE(RandomAccessFile out, long num, int len) throws IOException {
        for (int i = 0; i < len; i++) out.write((int) (num >> (8 * i)) & 255);
    }
    private static String name(String prefix, String extension) {
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date());
        return prefix + "_" + stamp + extension;
    }
    private static boolean allowed(Context context, String permission) {
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }
    private static PowerManager.WakeLock acquireWakeLock(Context context, long millis) {
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        if (pm == null) return null;
        PowerManager.WakeLock lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Jocotoco:Capture");
        lock.acquire(millis);
        return lock;
    }
}
