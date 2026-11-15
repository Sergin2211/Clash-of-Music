package com.clashofmusic.app.data.downloader;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Bundle;
import java.io.Closeable;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.json.JSONArray;
import org.json.JSONObject;

/** Pure Java: a debug test runner cannot rely on Kotlin symbols renamed in the optimized target. */
public final class OptimizedDownloadInstrumentation extends Instrumentation {
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }

    @Override public void onStart() {
        Context context = getTargetContext();
        File directory = new File(context.getCacheDir(), "optimized_downloader_" + UUID.randomUUID());
        JSONObject report = new JSONObject();
        JSONArray checks = new JSONArray();
        boolean success = false;
        try {
            require((context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0, "Target must be optimized");
            checks.put("target-is-optimized-non-debuggable-app");
            ClassLoader loader = context.getClassLoader();
            Class<?> field = Class.forName("org.apache.commons.compress.archivers.zip.AsiExtraField", true, loader);
            require(!Modifier.isAbstract(field.getModifiers()), "ZIP extra field became abstract");
            field.getConstructor().newInstance();
            checks.put("reflective-zip-field-remains-concrete-and-instantiable");
            require(directory.mkdirs(), "Private fixture directory");
            File archive = new File(directory, "runtime.zip");
            byte[] payload = "private runtime regression".getBytes(StandardCharsets.UTF_8);
            try (ZipOutputStream out = new ZipOutputStream(new java.io.FileOutputStream(archive))) {
                out.putNextEntry(new ZipEntry("fixture.txt")); out.write(payload); out.closeEntry();
            }
            Class<?> zipType = Class.forName("org.apache.commons.compress.archivers.zip.ZipFile", true, loader);
            Class<?> entryType = Class.forName("org.apache.commons.compress.archivers.zip.ZipArchiveEntry", true, loader);
            try (Closeable zip = (Closeable) zipType.getConstructor(File.class).newInstance(archive)) {
                Object entry = zipType.getMethod("getEntry", String.class).invoke(zip, "fixture.txt");
                require(entry != null, "ZIP entry missing");
                try (InputStream input = (InputStream) zipType.getMethod("getInputStream", entryType).invoke(zip, entry)) {
                    java.io.ByteArrayOutputStream data = new java.io.ByteArrayOutputStream();
                    byte[] buffer = new byte[1024]; int count;
                    while ((count = input.read(buffer)) != -1) data.write(buffer, 0, count);
                    require(Arrays.equals(payload, data.toByteArray()), "ZIP extraction changed bytes");
                }
            }
            checks.put("actual-optimized-zip-parser-initializes-and-extracts-fixture");
            for (String name : new String[] {"com.yausername.youtubedl_android.YoutubeDL", "com.yausername.ffmpeg.FFmpeg"}) {
                Class<?> type = Class.forName(name, true, loader);
                Object instance = type.getMethod("getInstance").invoke(null);
                type.getMethod("init", Context.class).invoke(instance, context);
            }
            checks.put("bundled-python-and-ffmpeg-initialize-in-optimized-apk");
            File output = new File(directory, "converted.mp3");
            ProcessBuilder builder = new ProcessBuilder(new File(context.getApplicationInfo().nativeLibraryDir,
                "libffmpeg.so").getAbsolutePath(), "-v", "error", "-f", "lavfi", "-i",
                "anullsrc=r=44100:cl=stereo", "-t", "0.2", "-c:a", "libmp3lame", "-q:a", "0", output.getAbsolutePath());
            File packages = new File(context.getNoBackupFilesDir(), "youtubedl-android/packages");
            builder.environment().put("LD_LIBRARY_PATH", new File(packages, "ffmpeg/usr/lib").getAbsolutePath()
                + ":" + new File(packages, "python/usr/lib").getAbsolutePath()
                + ":" + new File(packages, "aria2c/usr/lib").getAbsolutePath());
            Process process = builder.redirectErrorStream(true).start();
            java.io.ByteArrayOutputStream messages = new java.io.ByteArrayOutputStream();
            try (InputStream input = process.getInputStream()) {
                byte[] buffer = new byte[1024]; int count;
                while ((count = input.read(buffer)) != -1) messages.write(buffer, 0, count);
            }
            require(process.waitFor() == 0, messages.toString("UTF-8"));
            require(output.isFile() && output.length() > 0, "MP3 conversion produced no audio");
            checks.put("optimized-native-engine-converts-private-audio-to-mp3");
            success = true;
        } catch (Throwable error) {
            try { report.put("error", android.util.Log.getStackTraceString(error)); } catch (Exception ignored) { }
        } finally {
            deleteFixture(directory);
        }
        try { report.put("success", success).put("checks", checks); } catch (Exception ignored) { }
        Bundle result = new Bundle(); result.putString("stream", report.toString());
        finish(success ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
    }

    private static void require(boolean ok, String message) { if (!ok) throw new IllegalStateException(message); }
    private static void deleteFixture(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteFixture(child);
        file.delete();
    }
}
